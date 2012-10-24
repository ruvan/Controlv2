/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

import java.util.*;

/**
 *
 * @author Tyrone
 */
public class KineticSequenceComparator implements Comparator<KineticSequence> {
    @Override
    public int compare(KineticSequence k1, KineticSequence k2) {
        if(k1.override) {
            return 1;
        } else if (k2.started) {
            return -1;
        } else {
            return 0;
        }
    }
}
// return -1 if k1 goes behind k2