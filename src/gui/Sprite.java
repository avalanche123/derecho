package gui;

import ijeoma.motion.Motion;

import java.util.HashSet;
import java.util.Set;

import processing.core.PVector;

import com.google.common.collect.ImmutableSet;

/**
 * A named Drawable object with position and opacity. May have associated tweens for changing state over time. 
 * 
 * This class also handles the transition between motion (Open GL) and static (Java 2D) rendering, with some overlap
 * to prevent flickering.
 * 
 * @author <a href="mailto:krokicki@gmail.com">Konrad Rokicki</a>
 */
public abstract class Sprite implements Drawable {

    protected int countdown = 0;
    protected String name;
    protected String tooltip;
    protected PVector pos = new PVector(0, 0);
    protected float opacity = 255;
    private Set<Motion> tweens = new HashSet<Motion>();

    public Sprite(PVector pos) {
        setPos(pos);
    }

    public void update() {
        if (countdown > 0) countdown--;
        if (isInMotion()) {
            Set<Motion> toRemove = new HashSet<Motion>();
            synchronized (tweens) {
                for (Motion m : tweens) {
                    if (!m.isPlaying()) {
                        if (m.getPosition() == 0) {
                            m.play();
                        }
                        else {
                            toRemove.add(m);
                        }
                    }
                    else {
                        m.update();
                    }
                }
                for (Motion m : toRemove) {
                    tweens.remove(m);
                    if (!isInMotion()) {
                        // This sprite just stopped, start the Schrodinger countdown during which it appears both static and in motion
                        countdown = 30;
                    }
                }
            }
        }
    }

    public boolean isStatic() {
        return countdown > 0 || tweens.isEmpty();
    }

    public boolean isInMotion() {
        return countdown > 0 || !tweens.isEmpty();
    }

    public int getCountdown() {
        return countdown;
    }

    public void setCountdown(int countdown) {
        this.countdown = countdown;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTooltip() {
        return tooltip;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    public PVector getPos() {
        return pos;
    }

    public void setPos(PVector pos) {
        if (pos != null) {
            this.pos.set(pos.x, pos.y, 0);
        }
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public Set<Motion> getTweens() {
        synchronized (tweens) {
            return ImmutableSet.copyOf(tweens);
        }
    }

    public void addTween(Motion tween) {
        synchronized (tweens) {
            tweens.add(tween);
        }
    }
}
