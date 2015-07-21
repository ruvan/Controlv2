package controlv2;

import java.util.*;

/**
 *
 * @author Ruvan Muthu-Krishna
 * 
 * Return -1 if k1 goes behind k2
 */
public class KineticSequenceComparator implements Comparator<KineticSequence> {
    @Override
    public int compare(KineticSequence k1, KineticSequence k2) {
        if(k1.override) {
            return -1;
        } else if (k2.started) {
            return 1;
        } else {
            return 0;
        }
    }
}