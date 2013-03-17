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
public class KineticSequence {
    boolean override = false;
    boolean started = false;
    boolean finished = false;
    boolean isReaction = false;
    boolean add;
    String sequenceName;
    HashMap<String, Object> map;
    public KineticSequence(String sequenceName, boolean override, boolean add) {
        this.sequenceName = sequenceName;
        this.override = override;
        this.add = add;
    }
}
