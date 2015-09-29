package com.votors.umls.graph;

import org.jgrapht.*;
import org.jgrapht.graph.ListenableDirectedGraph;

import java.io.Serializable;

/**
 * Created by Jason on 2015/9/26 0026.
 */
public class UmlsVertex implements Serializable {
    private String aui = null;
    private String auiStr = null;
    /*status of the vertex: "root" or "child" */
    public static final String ROOT = "root";
    public static final String ROOT_NEW = "new-root";
    public static final String CHILD = "child";
    public String status = ROOT;
    public UmlsVertex root = this;   // who is the root of this vertex
    public int groupId = 0;     // which group this vertex belong to; 0 is no group yet.
    transient private ListenableDirectedGraph g = null;

    public UmlsVertex(String aui) {
        this.aui = aui;
    }
    public String getAui() { return aui;}
    public void setGraph(ListenableDirectedGraph graph) {g = graph;}
    public int getOutDegree() { if (g == null) return -1; else return g.outDegreeOf(this);}
    public int getInDegree() { if (g == null) return -1; else return g.inDegreeOf(this);}
    public void setAuiStr(String str) { auiStr = str;}
    public String getAuiStr() { return auiStr;}

    @Override public String toString () { return groupId + ":" + aui + "\n" + auiStr; }
    @Override public int hashCode() {return aui.hashCode();}
    @Override public boolean equals(Object obj) {
        if ((obj instanceof UmlsVertex) && aui.equals(((UmlsVertex)obj).aui)) {
            return true;
        }
        return false;
    }

    public String toString2() {
        return "Aui:" + aui + ",\tstatus: " + status + ",\tgroupId: " + groupId + ",\troot: "
                + root.getAui() + ",\tout: " + getOutDegree() + ",\tin: " + getInDegree() +",\tg: " + g.hashCode()
                + ",\trootid: " + root.hashCode();
    }
}