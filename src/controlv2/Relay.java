
package controlv2;

/**
 *
 * @author Tyrone
 */

public class Relay {
    
    private boolean state; // true if open
    private int strokes = 0;
    
    public void Relay(boolean state){
        this.state = state;
    }
    
    public void Relay() {
        state = false;
    }
    
    public boolean getState() {
        return state;
    }
    
    public void setState(boolean newState) {
        if(newState!=state) { // will perform a stroke
            strokes++;
        }
        state = newState;
    }
    
    public void toggleState() {
        if(state) {
            setState(false);
        }else{
            setState(true);
        }
    }
    
}
