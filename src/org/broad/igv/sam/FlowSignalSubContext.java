package org.broad.igv.sam;

import java.util.Arrays;

/**
 * Represents a flow signals context in an alignment block focused on a given base.  Added to support IonTorrent alignments.
 *
 * @author Nils Homer
 * @date 4/11/12
 */
public class FlowSignalSubContext {
    short[][] signals = null;
    char[][] bases = null;
    short[][] flownumbers = null;

    public FlowSignalSubContext(short[][] signals, char[][] bases, short[][] flownumbers ) {
        this.signals = signals;
        this.bases = bases;
        this.flownumbers = flownumbers;
    }   
} 
