/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

/**
 *
 * @author Tyrone
 */
public class Flower {
    Petal[] petals = new Petal[3];
    int[] upPetal = {4,2,6};
    int[] downPetal = {5,7,3};
    
    public Flower(int level, int flowerNumber, Relay[][] relayTable) {
        // Check if level is even or odd
        if(level % 2 == 0) { // even level
            // Check if flowerNumber is even or odd
            if(flowerNumber % 2 == 0) { //even
                // 4, 2, 6
                setPetals(upPetal, level, flowerNumber, relayTable);
            }else{ // odd
                // 5, 7,3
                setPetals(downPetal, level, flowerNumber, relayTable);
            }
        }else{ // odd level
            // Check if flowerNumber is even or odd
            if(flowerNumber % 2 == 0) { //even
                // 5, 7,3
                setPetals(downPetal, level, flowerNumber, relayTable);
            }else{ // odd
                // 4, 2, 6
                setPetals(upPetal, level, flowerNumber, relayTable);
            }
        }
        
    }
    
    public void setPetals(int[] petalType, int level, int flowerNumber, Relay[][] relayTable){
        for(int i=0; i<3; i++) {
            Relay tempRelay = new Relay();
            relayTable[petalType[i]+(level)*6-1][(flowerNumber/2)+1] = tempRelay;
            petals[i] = new Petal(petalType[i],tempRelay);
        }
    }
    
    public void allOn(){
        for(int i=0; i<3; i++) {
            petals[i].relay.setState(true);
        }
    }
       
    public void allOff(){
        for(int i=0; i<3; i++) {
            petals[i].relay.setState(false);
        }
    }
    
    
    
}
