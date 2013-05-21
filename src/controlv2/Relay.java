
package controlv2;

/**
 *
 * @author Tyrone
 */

public class Relay {
    
    private boolean state; // true if open
    private int strokes = 0;
    long lastTriggeredTime;
    
    public void Relay(boolean state){
        this.state = state;
        lastTriggeredTime = System.currentTimeMillis();
    }
    
    public void Relay() {
        state = false;
        lastTriggeredTime = System.currentTimeMillis();
    }
    
    public boolean getState() {
        return state;
    }
    
    public void setState(boolean newState) {
        if(newState!=state) { // will perform a stroke
            strokes++;
        }
        state = newState;
        lastTriggeredTime = System.currentTimeMillis();
    }
    
    public void toggleState() {
        if(state) {
            setState(false);
        }else{
            setState(true);
        }
    }
    
    public long getLastTriggeredTime() {
        return lastTriggeredTime;
    }
    
    public int getStrokes() {
        return strokes;
    }
    
    public void resetStrokes() {
        strokes=0;
    }
    
}
