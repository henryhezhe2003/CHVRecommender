package com.votors.umls

import java.io._
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern
import java.util.{Date, Properties}

import com.votors.common.{SqlUtils, Conf}
import com.votors.ml.{Clustering, Nlp}
import opennlp.tools.sentdetect.{SentenceDetectorME, SentenceModel}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkContext, SparkConf}

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.immutable.{List, Range}
import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.io.Source
import scala.io.Codec

import org.apache.commons.lang3.StringUtils
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.snowball.SnowballFilter
import org.apache.lucene.util.Version
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.impl.HttpSolrServer
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest
import org.apache.solr.common.SolrDocumentList
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.util.ContentStreamBase
import com.votors.common.Utils._
import com.votors.common.Utils.Trace._

import opennlp.tools.cmdline.BasicCmdLineTool
import opennlp.tools.cmdline.CLI
import opennlp.tools.cmdline.PerformanceMonitor
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSSample
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.tokenize.WhitespaceTokenizer
import opennlp.tools.util.ObjectStream
import opennlp.tools.util.PlainTextByLineStream
import java.sql.{Statement, Connection, DriverManager, ResultSet}

import org.apache.commons.csv._

/**
 * Suggestion is the result of a match.
 *
 * @param score
 * @param descr
 * @param cui
 * @param aui
 */
case class Suggestion(val score: Float,
                      val descr: String, val cui: String, val aui: String, val sab: String, val NormDescr: String="") {
  override
  def toString(): String = {
      "[%2.2f%%] (%s) (%s) (%s) %s".format(score, cui, aui, sab, descr)
  }
}

case class TargetTermsIndex(val id: Long, val cui: String, val aui: String, val sab: String,
                            val descr:String, val descr_norm:String, val descr_sorted:String, val descr_stemmed:String) {
  override
  def toString(): String = {
    "%d,%s,%s,%s,%s,%s,%s,%s".format(id, cui, aui, sab, descr,descr_norm,descr_sorted,descr_stemmed)
  }
  def getHead(): String = {
    "id,cui,aui,sab,descr,descr_norm,descr_sorted,descr_stemmed"
  }
  def createTableSql(tblName:String) = {
    val str=s"create table if not exists ${tblName} (`id` bigint default 0,`cui` varchar(100) DEFAULT NULL," +
      "`aui` varchar(100) DEFAULT NULL,`sab` varchar(100) DEFAULT NULL,`descr` text DEFAULT NULL," +
      "`descr_norm` text DEFAULT NULL,`descr_stemmed` text DEFAULT NULL,`descr_sorted` text DEFAULT NULL)"
    println(str)
    str
  }
  def insertSql(tblName:String) = {
    val str= s"insert into ${tblName} values ($id,'$cui','$aui','$sab','${descr.replaceAll("\'","\\\\'")}','$descr_norm','$descr_stemmed','$descr_sorted')"
    //println(str)
    str
  }
  def createIndexSql(tblName:String) = {
    val str=s"CREATE INDEX PIndex ON ${tblName} ( cui,descr (32),descr_norm (32),descr_sorted (32),descr_stemmed (32) );"
    println(str)
    str
  }
}

/*the output format for tag parsing*/
/**
 *
 * @param blogid the blog id
 * @param target the tag of the blog
 * @param umlsFlag if it is matched by UMLS, TRUE; else false
 * @param score the score to metric how much the target word match the AUI's string
 * @param cui CUI
 * @param sab source of the CUI
 * @param aui auto unique id?
 * @param desc the value of STR file in MRCONSON table
 * @param tui the semantic type id
 * @param styName the semantic type name
 * @param semName the semantic group name
 * @param tagId if the target word is a tag, this is the index of the tag; if not a tag, this is 0.
 * @param wordIndex the position of the target word in the content
 */
case class TagRow(val blogid: String, val target: String, val umlsFlag: Boolean,
                  val score: Float, val cui: String, val sab: String="", val aui: String="", val desc: String="",
                  val tui: String="", styName: String="", val semName: String="",
                  val tagId: Int=0, val wordIndex: Int=0, val wordIndexInSentence: Int=0, val sentenceIndex: Int=0,
                  val targetNorm: String="", val tags: String="", val sentence: String=""){
  override def toString(): String = {
    val str = f""""${blogid.trim}","${target.trim}","${if(umlsFlag)'Y' else 'N'}","${score}%2.2f","${cui}","${sab}","${aui}","${desc}","${tui}","${styName}","${semName}","${tagId}","${wordIndex}","${wordIndexInSentence}","${sentenceIndex}","${targetNorm}","${tags}","${sentence}"\n"""
    trace(INFO, "Get Tag parsing result: " + str)
    str
  }
  def getTitle(): String = {
    """"id","target","umlsFlag","score","cui","sab","aui","umlsStr","tui","styName","semName","tagId","wordIndex","wordIndexInSentence","sentenceIndex","targetNorm","tags","sentence"""" + "\n"
  }
}


/**
 * Main entry of the project.
 * Currently, the @select method is the most important function.
 *
 * @param solrServerUrl: the solr server url.
 * @param rootDir the dir of model files for opennlp
 */
class UmlsTagger2(val solrServerUrl: String=Conf.solrServerUrl, rootDir:String=Conf.rootDir) {

  val solrServer = new HttpSolrServer(solrServerUrl)

  //opennlp models path
  val modelRoot = rootDir + "/data"
  val posModlePath = s"${modelRoot}/en-pos-maxent.bin"
  val sentModlePath = s"${modelRoot}/en-sent.bin"

  /**
   *  load SemGroups.txt. The format of the file is "Semantic Group Abbrev|Semantic Group Name|TUI|Full Semantic Type Name"
   *  see: http://metamap.nlm.nih.gov/SemanticTypesAndGroups.shtml
  */
  val tuiMap = new mutable.HashMap[String, String]()
  Source.fromFile(s"${rootDir}/data/SemGroups.txt")
    .getLines()
    .foreach(line => {
      val tokens = line.split('|')
      if (tokens.length>=4) {
        tuiMap.put(tokens(2), tokens(1))
      }
  })
  //println(tuiMap.mkString(";"))

  // debug level, default is INFO
  //Trace.currLevel = WARN

  /**
   * Normalization:
   * - step 1: case and punctuation delete
   * - step 2: stem
   * - step 3: sort
   *
   * @param inputFile: csv format or table split file
   * @param outputFile:
   */
  def buildIndexJson(inputFile: File,
                     outputFile: File): Unit = {

    implicit val codec = Codec("UTF-8")
    codec.onMalformedInput(CodingErrorAction.REPLACE)
    codec.onUnmappableCharacter(CodingErrorAction.REPLACE)

    var writer = new PrintWriter(new FileWriter(outputFile))
    writer.println("[")
    var i = 0
    var cntByte = 0
    var cntFile = 1
    var newFile = true
    Source.fromFile(inputFile)
      .getLines()
      .foreach(line => {
      val Array(cui, aui, sab, str) = line
        .replace("\",\"", "\t")
        .replaceAll("\"", "")
        .replaceAll("\\\\", "")
        .split("\t")
      // the string in a Glossary is always considered as a sentence. No sentence detecting step.
      val strNorm = normalizeCasePunct(str)
      //val strPos = getPos(strNorm.split("")).sorted.mkString("+")
      val strStemmed = stemWords(strNorm)
      val strSorted = sortWords(strStemmed)
      val obuf = new StringBuilder()
      if (newFile == false) obuf.append(",")
      newFile = false
      obuf.append("{")
        .append("\"id\":").append(i).append(",")
        .append("\"cui\":\"").append(cui).append("\",")
        .append("\"aui\":\"").append(aui).append("\",")
        .append("\"sab\":\"").append(sab).append("\",")
        .append("\"descr\":\"").append(str).append("\",")
        .append("\"descr_norm\":\"").append(strNorm).append("\",")
        .append("\"descr_sorted\":\"").append(strSorted).append("\",")
        .append("\"descr_stemmed\":\"").append(strStemmed).append("\"")
        .append("}")
      writer.println(obuf.toString)
      i += 1
      cntByte += obuf.toString().length

      //Avoid the file is too big. it will fail to import to solr if the file bigger than 2.5G
      if (cntByte > 1*1024*1024*1024) {
        // close the old writer
        println(cntByte)
        writer.println("]")
        writer.flush()
        writer.close()
        // create a new writer
        cntByte = 0
        cntFile += 1
        newFile = true
        writer = new PrintWriter(new FileWriter(outputFile + s"($cntFile)"))
        writer.println("[")
      }

    })
    writer.println("]")
    writer.flush()
    writer.close()
  }

  def buildIndexCsv(inputFile: File,
                     outputFile: File): Unit = {

    implicit val codec = Codec("UTF-8")
    codec.onMalformedInput(CodingErrorAction.REPLACE)
    codec.onUnmappableCharacter(CodingErrorAction.REPLACE)

    var writer = new PrintWriter(new FileWriter(outputFile))
    writer.println(TargetTermsIndex(0,"","","","","","","").getHead())
    var i = 0
    var cntByte = 0
    var cntFile = 1
    var newFile = true
    Source.fromFile(inputFile)
      .getLines()
      .foreach(line => {
      val Array(cui, aui, sab, str) = line
        .replace("\",\"", "\t")
        .replaceAll("\"", "")
        .replaceAll("\\\\", "")
        .replaceAll(",", " ")
        .split("\t")
      // the string in a Glossary is always considered as a sentence. No sentence detecting step.
      val strNorm = normalizeCasePunct(str)
      //val strPos = getPos(strNorm.split("")).sorted.mkString("+")
      val strStemmed = stemWords(strNorm)
      val strSorted = sortWords(strStemmed)
      val obuf = new StringBuilder()
      if (newFile == false) obuf.append(",")
      newFile = false
      val targetTerm = TargetTermsIndex(i,cui,aui,sab,str,strNorm,strSorted,strStemmed)
      writer.println(targetTerm.toString())
      i += 1
      cntByte += obuf.toString().length

      //Avoid the file is too big. it will fail to import to solr if the file bigger than 2.5G
      if (cntByte > 1*1024*1024*1024) {
        // close the old writer
        println(cntByte)
        writer.println("]")
        writer.flush()
        writer.close()
        // create a new writer
        cntByte = 0
        cntFile += 1
        newFile = true
        writer = new PrintWriter(new FileWriter(outputFile + s"($cntFile)"))
        writer.println("[")
      }

    })
    writer.println("]")
    writer.flush()
    writer.close()
  }
  def buildIndex2db(inputFile: File): Unit = {
    implicit val codec = Codec("UTF-8")
    codec.onMalformedInput(CodingErrorAction.REPLACE)
    codec.onUnmappableCharacter(CodingErrorAction.REPLACE)
    val emptyTerm = TargetTermsIndex(0,"","","","","","","")
    // init table
    if (Conf.targetTermTblDropAndCreate) execUpdate(s"drop table if exists ${Conf.targetTermTbl}")
    execUpdate(emptyTerm.createTableSql( Conf.targetTermTbl))

    var i = 0
    Source.fromFile(inputFile)
      .getLines()
      .foreach(line => {
      val Array(cui, str) = line
        .replace("\",\"", "\t")
        .replaceAll("\"", "")
        .replaceAll("\\\\", "")
        .replaceAll(",", " ")
        .split("\t")
        // the string in a Glossary is always considered as a sentence. No sentence detecting step.
        val strNorm = normalizeCasePunct(str)
        val aui = "null"
        val sab = "null"
        //val strPos = getPos(strNorm.split("")).sorted.mkString("+")
        val strStemmed = stemWords(strNorm)
        val strSorted = sortWords(strStemmed)
        val obuf = new StringBuilder()
        val targetTerm = TargetTermsIndex(i, cui, aui, sab, str, strNorm, strSorted, strStemmed)
        // skip first line
        if (i>0)execUpdate(targetTerm.insertSql(Conf.targetTermTbl))
        i += 1
        })
    if (Conf.targetTermTblDropAndCreate)execUpdate(emptyTerm.createIndexSql( Conf.targetTermTbl))

  }
  ///////////// phrase munging methods //////////////

  def normalizeCasePunct(str: String): String = {
    Nlp.normalizeCasePunct(str,null)
  }

  def sortWords(str: String): String = {
    val words = Nlp.getToken(str)
    Nlp.sortWords(words).mkString(" ")
  }

  def stemWords(str: String): String = {
    trace(DEBUG,s"before stem:${str}")
    val stemmedWords = Nlp.getToken(str).filter(false == Nlp.checkStopword(_)).map(Nlp.stemWords(_)).mkString(" ")
    trace(DEBUG,s"after stem :${stemmedWords}")
    stemmedWords
  }

  def normalizeAll(str: String, isSort:Boolean=true, isStem: Boolean=true): String = {
    var ret = normalizeCasePunct(str)
    if (isStem)ret = stemWords(ret)
    if (isSort)ret = sortWords(ret)
    ret
  }

  //get pos after case/punctuation deleted(input has done this work)
  // XXX: This may be not a correct approach! but we do not rely on POS too much
  def getPos(phraseNorm: Array[String]) = {
    Nlp.getPos(phraseNorm,false)
  }
  //get sentence
  def getSent(phrase: String) = {
    Nlp.getSent(phrase)
  }

  ///////////////// solr search methods //////////////
  /**
   * Select all the result in solr.
   * The input string has to be normalized(case/puntuation delete, stemed, sorted), then search in
   * solr. All the result from solr will be evaluated a score. The higher the score, the closer the
   * result relative to the input.
   *
   * @param phrase the words to be search in solr
   * @return all the suggestion result in an array, sorted by score.
   */
  def select(phrase: String): Array[Suggestion] = {
    val ret = if (Conf.targetTermUsingSolr)
      select_solr(phrase.replaceAll("\'","\\\\'"))
    else
      select_db(phrase.replaceAll("\'","\\\\'"))
    //println(s"select: ${phrase}, number ${ret.size}")
    ret.filter(_.sab.matches(Conf.sabFilter))
  }
  def select_solr(phrase: String): Array[Suggestion] = {
    val phraseNorm = normalizeCasePunct(phrase)
    val queryPos = getPos(phraseNorm.split(" ")).sorted.mkString("+")
    val phraseStemmed = stemWords(phraseNorm)
    val phraseSorted = sortWords(phraseStemmed)

    // construct query. boost different score to stress fields.
//    val query = """descr:"%s"^10 descr_norm:"%s"^5 descr_sorted:"%s" descr_stemmed:"%s"^2"""
//      .format(phrase, phraseNorm, phraseSorted, phraseStemmed)
    val query = """descr_norm:"%s"^5 descr_sorted:"%s" descr_stemmed:"%s"^2"""
      .format(phraseNorm, phraseSorted, phraseStemmed)
    //println(query)
    val params = new ModifiableSolrParams()
    params.add(CommonParams.Q, query)
    params.add(CommonParams.ROWS, String.valueOf(10000))
    params.add(CommonParams.FL, "*,score")
    val rsp = solrServer.query(params)
    val results = rsp.getResults()
    if (results.getNumFound() > 0L) {
      //trace(INFO,s"select get ${results.getNumFound()} result for [${phrase}].")
      val ret = results.iterator().map(sdoc =>{
        val descr = sdoc.getFieldValue("descr").asInstanceOf[String]
        val descr_norm = sdoc.getFieldValue("descr_norm").asInstanceOf[String]
        val descr_sorted = sdoc.getFieldValue("descr_sorted").asInstanceOf[String]
        val descr_stemmed = sdoc.getFieldValue("descr_stemmed").asInstanceOf[String]
        trace(DEBUG,s"result from solr: $descr, $descr_norm, $descr_sorted, $descr_stemmed")
        val cui = sdoc.getFieldValue("cui").asInstanceOf[String]
        val aui = sdoc.getFieldValue("aui").asInstanceOf[String]
        val sab = sdoc.getFieldValue("sab").asInstanceOf[String]
        val descrNorm = normalizeCasePunct(descr)
        val resultPos = getPos(descrNorm.split(" ")).sorted.mkString("+")
        val score = computeScore(descr,
          scala.collection.immutable.List(phrase, phraseNorm, phraseStemmed, phraseSorted, queryPos,resultPos), Conf.caseFactor)
        Suggestion(score, descr, cui, aui,sab,phraseSorted)
      }).toArray.sortBy(s => 1 - s.score) // Decrease
      ret
    } else Array()
  }

  ///////////////// solr search methods //////////////
  /**
   * Select all the result in Mysql
   * The input string has to be normalized(case/puntuation delete, stemed, sorted), then search in
   * database. All the result will be evaluated a score. The higher the score, the closer the
   * result relative to the input.
   *
   * @param phrase the words to be search in solr
   * @return all the suggestion result in an array, sorted by score.
   */
  def select_db(phrase: String): Array[Suggestion] = {
    val phraseNorm = normalizeCasePunct(phrase)
    val queryPos = getPos(phraseNorm.split(" ")).sorted.mkString("+")
    val phraseStemmed = stemWords(phraseNorm)
    val phraseSorted = sortWords(phraseStemmed)

    // construct query. boost different score to stress fields.
    val query = s"select * from ${Conf.targetTermTbl} where descr='%s'or descr_norm='%s'or descr_sorted='%s'or descr_stemmed='%s';"
      .format(phrase, phraseNorm, phraseSorted, phraseStemmed)
    trace(DEBUG, query)
    val rsp = execQuery(query)
    val suggs = new ArrayBuffer[Suggestion]()
    //trace(INFO,s"select get ${results.getNumFound()} result for [${phrase}].")
    while(rsp.next()) {
      val descr = rsp.getString("descr")
      val descr_norm = rsp.getString("descr_norm")
      val descr_sorted = rsp.getString("descr_sorted")
      val descr_stemmed = rsp.getString("descr_stemmed")
      trace(DEBUG,s"result from solr: $descr, $descr_norm, $descr_sorted, $descr_stemmed")
      val cui = rsp.getString("cui")
      val aui = rsp.getString("aui")
      val sab = rsp.getString("sab")
      val descrNorm = normalizeCasePunct(descr)
      val resultPos = getPos(descrNorm.split(" ")).sorted.mkString("+")
      val score = computeScore(descr,
        scala.collection.immutable.List(phrase, phraseNorm, phraseStemmed, phraseSorted, queryPos,resultPos), Conf.caseFactor)
      suggs.append(Suggestion(score, descr, cui, aui,sab,descr_sorted))
    }
    suggs.toArray.sortBy(s => 1 - s.score) // Decrease
  }


  /**
   * The score is affect by 3 facts:
   * 1. a level or a base score: the more steps of transforming the string, the lower base score.
   * 2. a distance: for now it is getLevenshteinDistance.
   * 3. pos discount: if the pos is not the same, make a discount to the score directly.
   *
   * L-dist(t,s) / len *
   *
   * @param s
   * @param candidates
   * @param caseFactor how much you care about case(lowcase/upcase), [0,,1],,
   * @return
   */
  def computeScore(s: String, candidates: List[String], caseFactor: Float=0.0f): Float = {
    trace(DEBUG, s"computeScore(): ${s}, " + candidates.mkString("[",",","]"))
    val levels = scala.collection.immutable.List(100.0F, 90.0F, 80.0F, 80.0F, 0f, 0f)
    var candLevels = mutable.HashMap[String,  Float]()
    val posFlag = candidates(5) == candidates(4) //pos

    val candidatesLevel = Array(
      (candidates(0),levels(0)),
      (candidates(1),levels(1)),
      (candidates(2),levels(2)),
      (candidates(3),levels(3))
    )

    val topscore = candidatesLevel.map(cl => {
      val candidate = cl._1
      val level = cl._2
      if (candidate != null) {
        val maxlen = Math.max(candidate.length(), s.length()).toFloat
        val dist_case = StringUtils.getLevenshteinDistance(candidate, s).toFloat
        val dist_no_case = StringUtils.getLevenshteinDistance(candidate.toLowerCase, s.toLowerCase).toFloat
        val dist = (dist_case * (1 - caseFactor) + dist_no_case * caseFactor)
        (candidate, (1.0F - (1.0F*dist / maxlen)) * level)
      } else {
        ("", 0.0f)
      }
    })
      .sortWith((a, b) => a._2 > b._2)
      .head
    if (posFlag != true)
      topscore._2 * 0.7f // pos are different, make a 30% penalty
    else
      topscore._2
  }


  /////////////////// select for a text file ////////////////////
  /**
   * find terms from dictionary for a field in a cvs file.
   *
   * @param csvFile: Field order 0:blogId, 1: hash_tag,
   * @param ngram the ngram limited
   */
  def annotateFile(csvFile: String, outputFile:String, targetIndex: Int, ngram:Int=3, delimiter:Char=',',separator:Char='\n'): Unit = {
    var writer = new PrintWriter(new FileWriter(outputFile))
    writer.print(TagRow("","",true,0,"","","","","","","",0,0).getTitle())
    //get text content from csv file
    val in = new FileReader(csvFile)
    val records = CSVFormat.DEFAULT
      .withRecordSeparator(separator)
      .withDelimiter(delimiter)
      .withSkipHeaderRecord(true)
      .withEscape('\\')
      .parse(in)
      .iterator()

    var lastRecord: CSVRecord = null
    val tagList = ListBuffer[String]()
    // for each row of csv file
    records.foreach(currRecord => {
      // if the blogId not changed, it means the tags from the same blogId
      var skipSameBlog = false
      if (lastRecord == null || lastRecord.get(0) == currRecord.get(0)) {
        skipSameBlog = true
      } else {
        skipSameBlog = false
      }
      // if current record is the last record, we have to process it now.
      if (!records.hasNext) {
        skipSameBlog = false
        // add current tag to the tag list
        if (currRecord.size >= 2) tagList += normalizeAll(currRecord.get(1))
        lastRecord = currRecord
      }

      val target = if (lastRecord != null)lastRecord.get(targetIndex) else ""

      if (target.length > 0 && skipSameBlog == false) {
        // process the newline as configuration. 1: replace with space; 2: replace with '.'; 0: do nothing
        val target_tmp = if (Conf.ignoreNewLine == 1) {
           target.replace("\r\n"," ").replace("\r", " ").replace("\n", ". ").replace("\"", "\'")
         } else if (Conf.ignoreNewLine == 2) {
           target.replace("\r\n",". ").replace("\r", ". ").replace("\n", ". ").replace("\"", "\'")
         } else {
          target.replace("\"", "\'")
        }

        //segment the target into sentences
        val sents = getSent(target_tmp)

        var sentencePosition = 0
          var sentenceIndex = 0
          sents.foreach(sent => {
            if (sent.length > 0) {
              //get tags based on sentence, the result is suggestions(list)  for each (noun) words in the sentence.
              val suggestionsList = annotateSentence(sent, ngram)
              if (suggestionsList != null && suggestionsList.length > 0) {
                // word index in the sentence
                var wordIndex_in_sent = 0
                sentenceIndex += 1
                // process each word's suggestions(list)
                suggestionsList.foreach(wordSuggestions => {
                  if (wordSuggestions._2.length > 0) {
                    wordIndex_in_sent += 1
                  }
                  // process eache suggestion for a word in the sentence
                  wordSuggestions._2.filter(sugg=>sugg.sab.matches("SNOMEDCT_US")).foreach(suggestion =>{
                    //get the tagIndex of the word match. 0: not match. start from 1 if matched
                    val tagIndex = tagList.indexOf(normalizeAll(wordSuggestions._1)) + 1
                    //get all tui from mrsty table.
                    val mrsty = getMrsty(suggestion.cui)
                    while (mrsty.next) {
                      //for each TUI, get their semantic type from SemGroups.txt
                      val tui = mrsty.getString("TUI")
                      val styname = mrsty.getString("STY")
                      val sty = tuiMap.get(tui)
                      writer.print(TagRow(lastRecord.get(0), wordSuggestions._1.trim, true,
                        suggestion.score, suggestion.cui, suggestion.sab, suggestion.aui, suggestion.descr,
                        tui,styname, sty.getOrElse(""),
                        tagIndex,sentencePosition+wordIndex_in_sent,wordIndex_in_sent,sentenceIndex,
                        normalizeAll(wordSuggestions._1.trim),tagList.mkString(","),sent))
                    }
                  })

                })
                sentencePosition += wordIndex_in_sent
              }
            }
          })
        }

      // update last record to current record
      lastRecord = currRecord
      if (skipSameBlog == false) {
        tagList.clear()
      }
      if (currRecord.size >= 2) tagList += normalizeAll(currRecord.get(1))
    })
  }

  /* not finish yet*/
  def annotateTextInDB(textId:String,sent:String, ngram:Int=3) = {
    var sentenceIndex = 0
    val retList = new ListBuffer[TagRow]()
    //get tags based on sentence, the result is suggestions(list)  for each (noun) words in the sentence.
    val suggestionsList = annotateSentence(sent, ngram)
    if (suggestionsList != null && suggestionsList.length > 0) {
      // word index in the sentence
      var wordIndex_in_sent = 0
      sentenceIndex += 1
      // process each word's suggestions(list)
      val ret = suggestionsList.foreach(wordSuggestions => {
        if (wordSuggestions._2.length > 0) {
          wordIndex_in_sent += 1
        }
        // process eache suggestion for a word in the sentence
        wordSuggestions._2.foreach(suggestion =>{
          //get the tagIndex of the word match. 0: not match. start from 1 if matched
          //get all tui from mrsty table.
          retList.append(TagRow(textId, wordSuggestions._1.trim, false,
            suggestion.score, suggestion.cui, suggestion.sab, suggestion.aui, suggestion.descr,
            "null","null", "null",
            0,0,wordIndex_in_sent,sentenceIndex,
            normalizeAll(wordSuggestions._1.trim),"null",sent))
        })
      })
    }
    retList
  }


  /**
   * find terms for a sentence.
   *
   * @param sentence
   * @param ngram
   * @ return arry of (target-word, Suggestions-of-target-word)
   */
  def annotateSentence(sentence: String, ngram:Int=5): ListBuffer[(String,Array[Suggestion])] = {
    trace(DEBUG,"\nsentence:" + sentence)
    val sentenceNorm = normalizeCasePunct(sentence)
    //val sentencePosFilter = posFilter(sentenceNorm)
    var retList = new ListBuffer[(String,Array[Suggestion])]()
    val tokens = sentenceNorm.split(" ")
    for (n <- Range(ngram,0,-1)) {
      trace(DEBUG,"  gram:" + n)
      if (tokens.length >= n) {
        for (idx <- 0 to (tokens.length - n)) {
          val gram = tokens.slice(idx,idx+n)
          val pos = getPos(gram)
          if (Conf.posInclusive.length == 0 || posContains(gram,Conf.posInclusive)) {
            select(gram.mkString(" ")) match {
              case suggestions: Array[Suggestion] => {
                retList :+= (gram.mkString(" "), suggestions)
              }
              case _ => null
            }
          }
          else {
            null
          }
        }
      }
    }
    retList
  }


  /**
   *Get the UMLS term with the best score.
   * @param currTag
   * @return ((umlsScore,chvScore,umlsCui,chvCui), semanticTypeArray)
   */
  def getUmlsScore(currTag: String, getStys:Boolean=true): ((Double,Double,Suggestion,Suggestion),Array[Boolean]) = {
    var umlsScore = 0.0
    var umlsSugg:Suggestion = null
    var chvScore = 0.0
    var chvSugg:Suggestion = null
    var stys:Array[Boolean] = null

    select(currTag) match {
      case suggestions: Array[Suggestion] => {
        // for each UMLS terms, get their TUI from MRSTY table

        if (suggestions.length > 0) {
          suggestions.foreach(suggestion => {
            if (suggestion.sab.contains("CHV")) {
              if (suggestion.score > chvScore) {
                chvScore = suggestion.score
                chvSugg = suggestion
              }
            }
            if (suggestion.score > umlsScore) {
              umlsScore = suggestion.score
              umlsSugg = suggestion
            }
          })
          if (getStys && umlsScore > 0.01) {
            stys = Array.fill(Conf.semanticType.size)(false)
            suggestions.foreach(suggestion => {
              //get all tui from mrsty table.
              val mrsty = getMrsty(suggestion.cui)
              while (mrsty.next) {
                //for each TUI, get their semantic type
                val tui = mrsty.getString("TUI")
                val index = Conf.semanticType.indexOf(tui)
                if (index >= 0) {
                  stys(index) = true
                }
              }
            })
          }
        }
        }
    }
    ((umlsScore,chvScore,umlsSugg,chvSugg), stys)
  }

  /**
   * Match some 'tags' to dictionary(e.g. UMLS), and get their semantic type.
   *
   * @param tagFile
   */
  def annotateTag(tagFile: String, outputFile: String,targetIndex:Int=1,sep:String="\t"): Unit = {
    val source = Source.fromFile(tagFile, "UTF-8")
    var writer = new PrintWriter(new FileWriter(outputFile))
    writer.print(TagRow("","",true,0,"","","","","","","",0,0).getTitle())
    val lineIterator = source.getLines
    // for each tag, get the UMLS terms
    var tagId = 0
    var lastBlogId = ""
    lineIterator.foreach(line =>{
      if (line.trim().length>0) {
        val tokens = line.split(sep)
        if (tokens.length>targetIndex) {
          //get all terms from solr
          val currTag = tokens(targetIndex).trim
          val currBlogId = tokens(0).trim
          tagId = if (lastBlogId == currBlogId) tagId + 1 else 1
          lastBlogId = currBlogId
          //println(currTag)
          select(currTag) match {
            case suggestions: Array[Suggestion] => {
              // for each UMLS terms, get their TUI from MRSTY table
              if (suggestions.length > 0) {
                suggestions.foreach(suggestion => {
                  //get all tui from mrsty table.
                  println(suggestion)
                  val mrsty = getMrsty(suggestion.cui)
                  while (mrsty.next) {
                    //for each TUI, get their semantic type from SemGroups.txt
                    val tui = mrsty.getString("TUI")
                    val styname = mrsty.getString("STY")
                    val sty = tuiMap.get(tui)
                    writer.print(TagRow(currBlogId, tokens(1).trim, true,
                      suggestion.score, suggestion.cui, suggestion.sab, suggestion.aui, suggestion.descr,
                      tui,styname, sty.getOrElse(""),
                      tagId,0,0,0,normalizeAll(currTag),""))
                  }
                })
              } else {
                writer.print(TagRow(tokens(0), tokens(1).trim, false, 0, "", "", "", "", "", "", "",tagId,0))
              }
            }
            case _ => writer.print(TagRow(tokens(0), tokens(1).trim, false,0,"","","","","","", "",tagId,0))
          }
        }
      }
    })
    writer.flush()
    writer.close()
  }
  // the only different from 'annotateTag' is that it append the result to the original file format. should merge these two method.
  def annotateTagAppend(tagFile: String, outputFile: String,targetIndex:Int=1,sep:String="\t"): Unit = {
    val source = Source.fromFile(tagFile, "UTF-8")
    val writer = new PrintWriter(new FileWriter(outputFile))
    //writer.print(TagRow("","",true,0,"","","","","","","",0,0).getTitle())
    val lineIterator = source.getLines
    // for each tag, get the UMLS terms
    var lineCnt = 0
    lineIterator.foreach(line =>{
      if (line.trim().length>0) {
        lineCnt += 1
        var code = ""
        val tokens = line.split(sep)
        if (lineCnt == 1) code = "new-code"
        if (tokens.length>targetIndex && lineCnt > 1) {
          //get all terms from solr
          val currTag = tokens(targetIndex).trim
          //println(currTag)
          select(currTag) match {
            case suggestions: Array[Suggestion] => {
              // for each UMLS terms, get their TUI from MRSTY table
              if (suggestions.length > 0) {
                suggestions.foreach(suggestion => {
                  //get all tui from mrsty table.
                  println(suggestion)
                  val ret = execQuery(s"select code from umls.mrconso where CUI='${suggestion.cui}' and AUI='${suggestion.aui}';")
                  while (ret.next) {
                    //for each TUI, get their semantic type from SemGroups.txt
                    code += ret.getString("code") + ','
                  }
                })
              } else {
                //writer.println(line)
              }
            }
            case _ =>
          }
        }
        writer.println(line+"\t"+code)
      }
    })
    writer.flush()
    writer.close()
  }
  /**
   *
   * @param cui
   * @return
   */
  def getMrsty(cui: String): ResultSet = {
    execQuery(s"select * from umls.mrsty where CUI='${cui}';")
  }

  /**
   * If the sentence contains all the pos in $filter, return true, else false
   * @param phraseNorm the sentence to evaluate
   * @param filter the pos as a eligible criteria
   * @return true if eligible, else false
   */
  def posContains(phraseNorm: Array[String], filter:String="NN"):Boolean = {
    val retPos = Nlp.getPos(phraseNorm)
    trace(DEBUG, phraseNorm.mkString(",") + " pos is: " + retPos.mkString(","))

    var hit = false
    filter.split(" ").filter(_.length>0).foreach(p =>{
      if (retPos.indexOf(p) >= 0)
        hit = true
    })

    hit
  }

  private var isInitJdbc = false
  private var jdbcConnect: Connection = null
  private var sqlStatement: Statement = null
  def initJdbc() = {
    if (isInitJdbc == false) {
      isInitJdbc = true
      // Database Config
      val conn_str = Conf.jdbcDriver
      println("jdbcDrive is: " + conn_str)
      // Load the driver
      val dirver = classOf[com.mysql.jdbc.Driver]
      // Setup the connection
      jdbcConnect = DriverManager.getConnection(conn_str)
      // Configure to be Read Only
      sqlStatement = jdbcConnect.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
    }
  }
  val sqlUtil = new SqlUtils(Conf.dbUrl.toString)
  def jdbcClose() = sqlUtil.jdbcClose()
  def execQuery (sql: String):ResultSet = sqlUtil.execQuery(sql)
  def execUpdate (sql: String):Int = sqlUtil.execUpdate(sql)
}

object UmlsTagger2 {
  val tagger = new UmlsTagger2(Conf.solrServerUrl, Conf.rootDir)


  def main(args: Array[String]) {

//    println(tagger.stemWordsOrg("the man is happy."))

    println(s"The input is: ${args.mkString(",")}")
    if (args.length <3) {
      println("Input  error: args should be: rootdir inputFile outputFile. ")
      sys.exit(1)
    }
    val rootDir = args(0)

    //tagger.annotateTag(s"${rootDir}/data/taglist-zhiwei.txt",s"${rootDir}/data/taglist-zhiwei.csv")
    tagger.annotateTag(s"${args(1)}", s"${args(2)}")

    tagger.jdbcClose()
  }

}

object BuildTargetTerm {
  def main(args: Array[String]) = {
    println(s"The input is: ${args.mkString(",")}")
    if (args.length <1) {
      println("Input invalid: args should be: targetTermFile. The file format could be a [tab] separated or csv")
      sys.exit(1)
    }
    val tagger = new UmlsTagger2()
    tagger.buildIndex2db(new File(args(0)))
    println(s"target term is imported to table ${Conf.targetTermTbl} in Mysql")
  }
}

object IdentfyTargetTerm {

  def main(args: Array[String]) = {
    println(s"The input is: ${args.mkString(",")}")
    if (args.length <1) {
      println("Input invalid: args should be: targetTermFile. The file format could be a [tab] separated or csv")
      sys.exit(1)
    }

    // init spark
    val startTime = new Date()
    val conf = new SparkConf()
      .setAppName("NLP")
    if (Conf.sparkMaster.length>0)
      conf .setMaster(Conf.sparkMaster)
    val sc = new SparkContext(conf)

    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.WARN)

    // printf more debug info of the gram that match the filter
    Trace.filter = Conf.debugFilterNgram
    val clustering = new Clustering(sc)

    var writer = new PrintWriter(new FileWriter(args(0)))
    writer.print(TagRow("","",true,0,"","","","","","","",0,0).getTitle())
    val rdd = clustering.getBlogIdRdd(Conf.partitionNumber)
    val rddText = clustering.getBlogTextRdd(rdd)
    val rddSent = clustering.getSentRdd(rddText).persist()
    val rows = rddSent.flatMap(s=>s).mapPartitions(pt => {
      val tagger = new UmlsTagger2()
      val retList = new ListBuffer[TagRow]()
      pt.foreach(sent =>{
        retList ++= tagger.annotateTextInDB(sent.blogId.toString,sent.words.mkString(" "),5)
      })
      retList.iterator
    })
    rows.collect().foreach(r=>{
      writer.write(r.toString())
    })
    writer.close()
  }

}