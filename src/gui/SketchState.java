package gui;

import gui.Rectangle.Bounds;
import ijeoma.motion.tween.NumberProperty;
import ijeoma.motion.tween.Tween;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import processing.core.*;
import snapshot.Snapshot;
import timeline.*;
import util.ConfigProperties;

import com.google.common.collect.*;

/**
 * The SketchState is a background thread responsible for drawing things which are relatively static to a buffer. 
 * It has its own animation loop which tracks the timeline of the visualization at whatever rate the user selects. 
 * Because it is decoupled from the main animation loop, it has no specific timing it has to meet, so it can be 
 * relatively slow and precise. All of its drawing is done with pixel precision, using Java 2D instead of OpenGL. 
 * 
 * Anything which needs to be animated smoothly (e.g. job tweening) is left up to the enclosing sketch, and its 
 * main animation loop. 
 * 
 * @author <a href="mailto:krokicki@gmail.com">Konrad Rokicki</a>
 */
public class SketchState implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SketchState.class);

    // Timing
    private static final float DURATION_JOB_SUB = 40.0f;
    private static final float DURATION_JOB_START = 20.0f;
    private static final float DURATION_JOB_END = 70.0f;
    private static final float DISTANCE_JOB_START = 50.0f;

    // Heatmap Constants
    private static final int HEATMAP_BRIGHTNESS = 90;
    private static final int HEATMAP_SATURATION = 90;
    private static final int HEATMAP_MIN_HUE = 0;
    private static final int HEATMAP_MAX_HUE = 250;
    
    public class ColorScheme {
        int gridBackgroundColor;
        int gridBaseColor;
        int nodeBorderColor;
        int nodeBackgroundColor;
        int emptySlotColor;
        int timelineColor;
        int panelBorderColor;
        int graphLineColorRunningJobs;
        int graphLineColorQueuedJobs;
        int nodeFontColor;
        int titleFontColor;
    }

    public class DefaultColorScheme extends ColorScheme {
        DefaultColorScheme() {
            this.gridBackgroundColor = Utils.color("232847");
            this.gridBaseColor = Utils.color("4D5899");
            this.nodeBorderColor = gridBaseColor;
            this.nodeBackgroundColor = gridBackgroundColor;
            this.emptySlotColor = Utils.color("323966");
            this.timelineColor = Utils.color("323966");
            this.panelBorderColor = Utils.color("2C3259");
            this.graphLineColorRunningJobs = Utils.color("7E90FF");
            this.graphLineColorQueuedJobs = Utils.color("FFEC7E");
            this.nodeFontColor = gridBaseColor;
            this.titleFontColor = gridBaseColor;
        }
    }

    public class GrayColorScheme extends ColorScheme {
        GrayColorScheme() {
            this.gridBackgroundColor = Utils.color("111111");
            this.gridBaseColor = Utils.color("636363");
            this.nodeBorderColor = gridBaseColor;
            this.nodeBackgroundColor = Utils.color("000000");
            this.emptySlotColor = Utils.color("4D4D4D");
            this.timelineColor = Utils.color("292929");
            this.panelBorderColor = gridBaseColor;
            this.graphLineColorRunningJobs = Utils.color("CCCCCC");
            this.graphLineColorQueuedJobs = Utils.color("A8A8A8");
            this.nodeFontColor = gridBaseColor;
            this.titleFontColor = gridBaseColor;
        }
    }
    
    public class HeatmapColorScheme extends ColorScheme {
        HeatmapColorScheme() {
            this.gridBackgroundColor = Utils.color("000000");
            this.gridBaseColor = Utils.color("666666");
            this.nodeBorderColor = gridBaseColor;
            this.nodeBackgroundColor = gridBackgroundColor;
            this.emptySlotColor = Utils.color("4D4D4D");
            this.timelineColor = Utils.color("262626");
            this.panelBorderColor = gridBaseColor;
            this.graphLineColorRunningJobs = Utils.color("E61717");
            this.graphLineColorQueuedJobs = Utils.color("E6E617");
            this.nodeFontColor = gridBaseColor;
            this.titleFontColor = gridBaseColor;
        }
    }
    
    // Colors
    private ColorScheme colorScheme = new DefaultColorScheme();
    private final int laserColor = Utils.color("ff2222");
    private final int outlineColor = Utils.color("FFFFFF");
    
    // Formatting
    private final DateTimeFormatter df = DateTimeFormat.forPattern("hh:mmaaa");
    private final DateTimeFormatter dfDate = DateTimeFormat.forPattern("MM/dd/yyyy");
    
    // Invariants
    private final float width;
    private final float height;
    private final int nodeFontHeight;
    private final int legendFontHeight;
    private final int statsFontHeight;
    private final int titleFontHeight;
    private final PFont nodeFont;
    private final PFont legendFont;
    private final PFont statsFont;
    private final PFont titleFont;
    
    // Sizing variables
    private int numCols;
    private int numRows;
    private float topPadding = 40;
    private float padding = 20;
    private float rectSpacing = 10;
    private float nodeWidth;
    private float nodeHeight;
    private float nodeLabelHeight = 15;
    private float slotWidth;
    private float slotHeight;
    private float slotSpacing = 3;
    private float nodeSpacing = 7;
    private float timeLabelOffset = 4;
    private float queuedJobWidth = 10;
    private float queuedJobHeight = 10;
    private float queuedJobSpacing = 3;
    private int queueItemsPerLine = 100;
    
    // Bounds
    private Rectangle gridRect;
    private Rectangle queueRect;
    private Rectangle legendRect;
    private Rectangle summaryViewRect;
    private Rectangle graphRect;
    private Rectangle graphBodyRect;
    
    // Buffers
    private PApplet p = null;
    private PGraphics offscreenBuffer = null;
    private PGraphics onscreenBuffer = null;
    private PGraphics offscreenGraphBuffer = null;
    private PGraphics onscreenGraphBuffer = null;

    // Sprites
    private Legend legend;
    private SummaryView summaryView;
    private Map<String,NodeSprite> nodeSprites = new HashMap<String,NodeSprite>();
    private Map<String,SlotSprite> slotSprites = new HashMap<String,SlotSprite>();
    private LinkedList<String> queuedJobs = new LinkedList<String>();
    private Multimap<String, JobSprite> jobSpriteMap = Multimaps.synchronizedMultimap(HashMultimap.<String, JobSprite>create()); 
    private LineGraph runningJobsGraph;
    private LineGraph queuedJobsGraph;
    
    // Heatmap tracking
    private Map<String,Long> slotUsage = new HashMap<String,Long>();
    private Map<String,Long> slotStartOffsets = new HashMap<String,Long>();
    
    // Overall state
    private String title;
    private Timeline timeline;
    private boolean summaryMode = true;
    private boolean bwMode = false;
    
    // State for playing
    private PlayState playState = PlayState.PAUSED;
    private GridState state;
    private double playSpeed = 1.0f;
    private long totalElapsed = 0;
    private Date lastSliceRequestDate;
    private long nextStartingPosition = 0;
    private int maxGraphValue;

    // Draw outlines for UI components?
    private boolean isDrawOutlines = ConfigProperties.getBoolean("derecho.viz.draw.outlines",false);
    
    // Should changes to sprites be tweened? This is usually disabled during buffering, for example.
    private boolean tweenChanges = true;
    
    // Should jobs have lasers to show their intended routes?
    private boolean isDrawLaserTracking = false;

    // Show a heatmap instead of jobs?
    private boolean isHeatmap = false;
    
    // Draw snapshot lines on the graphs?
    private boolean isDrawSnapshotLines = false;
    
    public SketchState(PApplet p, Timeline timeline, float width, float height, String title) {
        this.p = p;
        this.title = title;
        this.width = width;
        this.height = height;
        this.timeline = timeline;
        this.nodeFontHeight = height<=1200 ? 8 : 10;
        this.legendFontHeight = height<=1200 ? 12 : 14;
        this.statsFontHeight = height<=1200 ? 12 : 14;
        this.titleFontHeight = height<=1200 ? 14 : 32;
        this.nodeFont = p.loadFont("LucidaConsole-"+nodeFontHeight+".vlw");
        this.legendFont = p.loadFont("LucidaConsole-"+legendFontHeight+".vlw");
        this.statsFont = p.loadFont("LucidaConsole-"+statsFontHeight+".vlw");
        this.titleFont = p.loadFont("LucidaConsole-"+titleFontHeight+".vlw");
    }
    
    //
    //  --totalElapsed-
    //  ----elapsed----
    // |---------------|--------------------------------------------------------
    // lastSliceDate   currDate
    //
    // --------totalElapsed------------
    //                  ----elapsed----
    // |---------------|---------------|----------------------------------------
    //                 lastSliceDate   currDate
    //
    //
    // --------------totalElapsed----------------------
    //                                  ----elapsed----
    // |-------------------------------|---------------|------------------------
    //                                 lastSliceDate   currDate
    //
    
    @Override
    public void run() {
        
        while (true) {
            
            switch (playState) {
            
            case BUFFERING:
                bufferToNextPosition();
                break;
                
            case PLAYING:
                Date currDate = new Date();
                long elapsed = (int)((currDate.getTime() - lastSliceRequestDate.getTime()) * playSpeed);
                this.lastSliceRequestDate = currDate;
                this.maxGraphValue =  (runningJobsGraph.getGraphMap().isEmpty()||queuedJobsGraph.getGraphMap().isEmpty())?0:(Math.max(Collections.max(runningJobsGraph.getGraphMap().values()),Collections.max(queuedJobsGraph.getGraphMap().values())));
                while (maxGraphValue%10>0 && maxGraphValue%5>0) {
                    maxGraphValue++;
                }
                
                runningJobsGraph.setMaxValue(maxGraphValue);
                queuedJobsGraph.setMaxValue(maxGraphValue);
                
                // Check if we've been truncated, and move forward if necessary
                if (totalElapsed < timeline.getFirstOffset()) {
                    log.info("Elapsed time ({}) occurs before the current timeline ({})",elapsed,timeline.getFirstOffset());
                    
                    long position = elapsed;
                    boolean noMatch = true;
                    while (noMatch) {
                        if (position != timeline.getFirstOffset()) {
                            position = timeline.getFirstOffset();
                            try {
                                Thread.sleep(500);  
                            }
                            catch (InterruptedException e) {
                                // Ignore
                            }
                        }
                        else {
                            noMatch = false;
                        }
                    }
                    log.info("Will buffer to new position: {}",position);
                    bufferAtPosition(position);
                    break;
                }

                // Update sprites and build a usage map             
                Map<String,Integer> slotsUsedByUser = new HashMap<String,Integer>();
                Map<String,Integer> slotsQueuedByUser = new HashMap<String,Integer>();
                Iterator<? extends Sprite> i = getJobSprites().values().iterator();
                while (i.hasNext()) {
                    Sprite sprite = i.next();
                    if (sprite instanceof JobSprite) {
                        JobSprite jobSprite = (JobSprite)sprite;
                        if (jobSprite.defunct) {
                            removeJobSprite(jobSprite.name, jobSprite);
                        }
                        else {
                            // Parse a jobId like this: 1275988.2828-4000:1
                            int slots = 1;
                            if (jobSprite.queued) {
                                // If a job is queued then it is represented by a single sprite, so we need the
                                // actual number of slots
                                GridJob job = state.getJobByFullId(jobSprite.name);
                                if (job!=null) {
                                    slots = job.getSlots();
                                }
                            }
                            
                            if (jobSprite.getName().contains(":")) {
                                try {
                                    Pattern p = Pattern.compile("(\\d+)\\.(\\d+)-(\\d+):(\\d+)");
                                    Matcher m = p.matcher(jobSprite.getName());
                                    if (m.matches()) {
                                        int start = Integer.parseInt(m.group(2));
                                        int end = Integer.parseInt(m.group(3));
                                        int interval = Integer.parseInt(m.group(4));
                                        slots = (end-start)/interval;
                                    }
                                } 
                                catch (Exception e) {
                                    log.error("Error parsing jobId: "+jobSprite.getName(),e);
                                }
                            }
                            
                            String user = jobSprite.getUsername();
                            if (!slotsUsedByUser.containsKey(user)) {
                                slotsUsedByUser.put(user, 0);
                            }
                            if (!slotsQueuedByUser.containsKey(user)) {
                                slotsQueuedByUser.put(user, 0);
                            }
                            if (jobSprite.queued) {
                                slotsQueuedByUser.put(user, slotsQueuedByUser.get(user)+slots);
                            }
                            else {
                                slotsUsedByUser.put(user, slotsUsedByUser.get(user)+slots);
                            }
                        }
                    }
                }
                
                legend.retain(slotsUsedByUser);
                summaryView.retain(slotsUsedByUser, slotsQueuedByUser);
                
                updateWindowSizes();
                recalculateQueuePacking();
                updateState(elapsed);
                updateOffscreenBuffer();
                updateOffscreenGraphBuffer();
                flipBuffers();
                
                break;

            case READY:
                break;
                
            case PAUSED:
                break;

            case END:
                return;
                
            default:
                log.error("Invalid play state: "+playState);
                break;
                
            }
            
            try {
                Thread.sleep(50);   
            }
            catch (InterruptedException e) {
                // Ignore
            }
        }
    }
    
    private void bufferToNextPosition() {

        log.info("Buffering to next position: {}",nextStartingPosition);
        
        int i=0;
        Snapshot reqSnapshot = null;
        Snapshot prevSnapshot = null;
        for(Snapshot snapshot : timeline.getSnapshots()) {
            long offset = timeline.getOffset(snapshot.getSamplingTime());
            
            log.info("Snapshot {} has offset {}",i,offset);
            
            if (offset>=nextStartingPosition) {
                if (prevSnapshot==null) {
                    reqSnapshot = snapshot;
                }
                else {
                    reqSnapshot = prevSnapshot;
                }
                break;
            }
            prevSnapshot = snapshot;
            i++;
        }
        
        if (reqSnapshot==null) {
            reqSnapshot = prevSnapshot;
            if (reqSnapshot==null) {
                log.error("Could not find snapshot for offset {}",nextStartingPosition);
                setPlayState(PlayState.PAUSED);
                return;
            }
        }
        
        // Initialize the state at the closest possible snapshot
        log.info("Init with snapshot with offset {}",timeline.getOffset(reqSnapshot.getSamplingTime()));
        
        this.state = new GridState(reqSnapshot,"runState");
        initState();
        
        // Apply all events between the closet snapshot and the desired starting position
        this.totalElapsed = timeline.getOffset((reqSnapshot.getSamplingTime()))+1;
        long elapsed = nextStartingPosition - totalElapsed;

        log.info("Buffering elapsed: {}",elapsed);
        this.tweenChanges = false;
        updateState(elapsed);
        this.tweenChanges = true;
        
        // Get ready to start playing
        this.lastSliceRequestDate = new Date();
        if (totalElapsed != nextStartingPosition) {
            totalElapsed = nextStartingPosition;
        }

        setPlayState(PlayState.READY);
        log.info("Buffered at totalElapsed={}",totalElapsed);
    }
    
    public synchronized void bufferAtPosition(long position) {
        if (playState==PlayState.PAUSED) {
            setPlayState(PlayState.BUFFERING);
            this.nextStartingPosition = position;
        }
        else {
            log.error("Cannot transition from "+playState+" to BUFFERING");
        }
    }
    
    public synchronized void play() {
        if (playState==PlayState.PAUSED || playState==PlayState.READY) {
            log.info("Beginning playback at totalElapsed={}",totalElapsed);
            this.lastSliceRequestDate = new Date();
            setPlayState(PlayState.PLAYING);
        }
        else {
            log.error("Cannot transition from "+playState+" to PLAYING");
        }
    }
    
    public synchronized void pause() {
        if (playState==PlayState.PLAYING || playState==PlayState.PAUSED) {
            setPlayState(PlayState.PAUSED);
        }
        else {
            log.error("Cannot transition from "+playState+" to PAUSED");
        }
    }
    
    public synchronized void end() {
        setPlayState(PlayState.END);
    }
    
    private void setPlayState(PlayState playState) {
        log.info("Entering state: {}",playState);
        this.playState = playState;
    }

    public boolean isPaused() {
        return playState==PlayState.PAUSED;
    }
    
    public boolean isReady() {
        return playState==PlayState.READY;
    }
    
    public boolean isPlaying() {
        return playState==PlayState.PLAYING;
    }
    
    public boolean isBuffering() {
        return playState==PlayState.BUFFERING;
    }

    public boolean isEnded() {
        return playState==PlayState.END;
    }
    
    public double getPlaySpeed() {
        return playSpeed;
    }

    public void setPlaySpeed(double playSpeed) {
        this.playSpeed = playSpeed;
    }

    public long getPosition() {
        return totalElapsed;
    }
    
    private void updateWindowSizes() {

        // Legend Window 
        float legendHeight = 10;
        if (legend!=null) legendHeight = legend.getHeight();
        this.legendRect = new Rectangle(padding, graphRect.getBounds().minY-legendHeight-rectSpacing, graphRect.getWidth(), legendHeight);
        if (legend!=null) legend.setRect(legendRect);
        
        // Summary Window
        this.summaryViewRect = new Rectangle(padding, gridRect.getBounds().maxY+rectSpacing, graphRect.getWidth(), graphRect.getBounds().minY-gridRect.getBounds().maxY-2*rectSpacing);
        
        // Queue Window
        this.queuedJobWidth = slotWidth; 
        this.queuedJobHeight = slotHeight;
        this.queueRect = new Rectangle(padding, gridRect.getBounds().maxY+rectSpacing, gridRect.getWidth(), legendRect.getBounds().minY - gridRect.getBounds().maxY - 2*rectSpacing);
        this.queueItemsPerLine = (int)Math.round(queueRect.getWidth() / (queuedJobWidth+queuedJobSpacing));
    }
    
    private void recalculateQueuePacking() {

        int total = 0;
        for(String fullJobId : queuedJobs) {
            total += jobSpriteMap.get(fullJobId).size();
        }
        
        float defaultSquare = slotWidth;
        float w = queueRect.getWidth();
        float h = queueRect.getHeight() - 2*padding;
        float bestSquare = bestSquare(w, h, total) - queuedJobSpacing;
        if (bestSquare>defaultSquare) bestSquare = defaultSquare;

        queuedJobWidth = bestSquare;
        queuedJobHeight = bestSquare;
        queueItemsPerLine = (int)Math.round(queueRect.getWidth() / (queuedJobWidth+queuedJobSpacing));
    }
    
    /**
     * Transliterated from Python solution available here: 
     * http://stackoverflow.com/questions/6463297/algorithm-to-fill-rectangle-with-small-squares
     */
    private int bestSquare(float w, float h, float n) {
        float hi = Math.max(w, h);
        float lo = 0;
        while (Math.abs(hi - lo) > 0.0001) {
            float mid = (lo+hi)/2;
            float midval = (float)Math.floor(w / mid) * (float)Math.floor(h / mid);
            if (midval >= n) {
                lo = mid;
            }
            else if (midval < n) {
                hi = mid;
            }
        }
        return (int)Math.min(w/Math.floor(w/lo), h/Math.floor(h/lo));
    }

    private PVector getQueuedPosition(String fullJobId, int i) {
        if (isSummaryMode()) {
            GridJob job = state.getJobByFullId(fullJobId);
            String username = job.getOwner();
            Rectangle rect = summaryView.getUserRect(username);
            if (rect!=null) { 
                return new PVector(rect.getBounds().minX+(rect.getWidth()-queuedJobWidth)/2, rect.getBounds().minY);
            }
        }
        
        float x = (queuedJobWidth + queuedJobSpacing) * ((float)i % (float)queueItemsPerLine);
        float y = (queuedJobHeight + queuedJobSpacing) * (float)Math.floor((float)i / (float)queueItemsPerLine);
        return PVector.add(queueRect.getPos(),new PVector(x,y+padding));
    }
    
    private void initState() {

        this.nodeSprites.clear();
        this.slotSprites.clear();
        this.queuedJobs.clear();
        this.jobSpriteMap.clear();
        this.slotUsage.clear();
        this.slotStartOffsets.clear();
        
        this.nodeSpacing = (float)width / (float)400;

        this.numRows = state.getNumRows();
        this.numCols = state.getNumCols();
        
        // These are defined in a specific order!
        
        // Grid Window
        float gridWidth = width-2*padding;
        this.nodeWidth = ((gridWidth + nodeSpacing) / numCols) - nodeSpacing;
        this.slotWidth = slotHeight = (nodeWidth - slotSpacing * 5) / 4;
        this.nodeHeight = slotSpacing * 3 + slotHeight * 2 + nodeLabelHeight;
        float gridHeight = (nodeHeight + nodeSpacing) * numRows - nodeSpacing;
        this.gridRect = new Rectangle(padding, padding+topPadding, gridWidth, gridHeight);

        // Graph Window
        float graphWindowWidth = gridWidth;
        float graphWindowHeight = Math.round((float)height*.15);
        float graphWindowX = padding;
        float graphWindowY = height-graphWindowHeight-padding;
        this.graphRect = new Rectangle(graphWindowX, graphWindowY, graphWindowWidth, graphWindowHeight); 

        // Graph Body
        float graphTopPadding =  Math.round((float)graphWindowHeight*.10);
        float graphBodyWidth = graphWindowWidth;
        float graphBodyHeight = graphWindowHeight-graphTopPadding*2;
        float graphBodyX = graphWindowX;
        float graphBodyY = graphWindowY+graphTopPadding;
        this.graphBodyRect = new Rectangle(graphBodyX, graphBodyY, graphBodyWidth, graphBodyHeight);
        Rectangle graphPaddedRect = new Rectangle(0, 5, graphBodyWidth, graphBodyHeight-10);

        // Size the dynamic windows
        updateWindowSizes();
        
        // Initialize Views
        this.legend = new Legend(legendRect, legendFont, legendFontHeight);
        this.summaryView = new SummaryView(summaryViewRect, statsFont, statsFontHeight, legend);

        // Initialize Graphs
        Map<Long, Integer> runningJobsMap = timeline.getNumRunningJobsMap();
        Map<Long, Integer> queuedJobsMap = timeline.getNumQueuedJobsMap();
        this.runningJobsGraph = new LineGraph(graphPaddedRect, timeline, runningJobsMap);
        this.runningJobsGraph.setColor(colorScheme.graphLineColorRunningJobs); 
        this.queuedJobsGraph = new LineGraph(graphPaddedRect, timeline, queuedJobsMap);
        this.queuedJobsGraph.setColor(colorScheme.graphLineColorQueuedJobs);
        
        // Initialize sprites from the grid state
        for (int i = 0; i < numCols; i++) {
            for (int j = 0; j < numRows; j++) {
                GridNode node = state.getGrid()[i][j];
                if (node==null) continue;
                PVector pos = new PVector((nodeWidth + nodeSpacing) * i, (nodeHeight + nodeSpacing) * j);
                NodeSprite nodeSprite = createNodeSprite(PVector.add(gridRect.getPos(), pos), node);
                
                nodeSprites.put(nodeSprite.name, nodeSprite);
                for(SlotSprite slotSprite : nodeSprite.slots) {
                    slotSprites.put(slotSprite.name, slotSprite);
                }
                
                int s = 0;
                for(GridJob job : node.getSlots()) {
                    if (job!=null) {
                        log.trace("  Adding running job {}",job.getFullJobId());
                        
                        SlotSprite slotSprite = nodeSprite.slots[s];
                        JobSprite jobSprite = createJobSprite(job, slotSprite.pos);
                        jobSprite.slotName = slotSprite.name;
                        
                        addJobSprite(job.getFullJobId(),jobSprite);
                        
                        log.debug("Starting job {} on slot: {}",job.getFullJobId(),slotSprite.name);
                        slotStartOffsets.put(slotSprite.name, nextStartingPosition);
                    }
                    s++;
                }
            }
        }
        
        int i = 0;
        for(GridJob job : state.getQueuedJobs()) {
            log.trace("  Adding queued job {}",job.getFullJobId());
            PVector pos = getQueuedPosition(job.getFullJobId(), i);
            JobSprite jobSprite = createJobSprite(job, pos);            
            jobSprite.queued = true;
            addJobSprite(job.getFullJobId(),jobSprite);
            queuedJobs.add(job.getFullJobId());
            i++;
        }
        
        // Initialize Buffers
        // The way this is done is very deliberate. The buffer needs to be fully initialized before being available 
        // to the sketch. If begin/end draw is not called before the field is set, then we get null pointers from AWT. 
        
        PGraphics onscreenBuffer = p.createGraphics((int)width, (int)height, PGraphics.JAVA2D);
        onscreenBuffer.smooth();
        onscreenBuffer.hint(PGraphics.ENABLE_NATIVE_FONTS);
        onscreenBuffer.beginDraw();
        onscreenBuffer.endDraw();
        this.onscreenBuffer = onscreenBuffer;
        
        PGraphics offscreenBuffer = p.createGraphics((int)width, (int)height, PGraphics.JAVA2D);
        offscreenBuffer.smooth();
        offscreenBuffer.hint(PGraphics.ENABLE_NATIVE_FONTS);
        offscreenBuffer.beginDraw();
        offscreenBuffer.endDraw();
        this.offscreenBuffer = offscreenBuffer;
        
        PGraphics onscreenGraphBuffer = p.createGraphics((int)graphBodyRect.getWidth(), (int)graphBodyRect.getHeight(), PGraphics.JAVA2D);
        onscreenGraphBuffer.smooth();
        onscreenGraphBuffer.hint(PGraphics.ENABLE_NATIVE_FONTS);
        onscreenGraphBuffer.beginDraw();
        onscreenGraphBuffer.endDraw();
        this.onscreenGraphBuffer = onscreenGraphBuffer;
        
        PGraphics offscreenGraphBuffer = p.createGraphics((int)graphBodyRect.getWidth(), (int)graphBodyRect.getHeight(), PGraphics.JAVA2D);
        offscreenGraphBuffer.smooth();
        offscreenGraphBuffer.hint(PGraphics.ENABLE_NATIVE_FONTS);
        offscreenGraphBuffer.beginDraw();
        offscreenGraphBuffer.endDraw();
        this.offscreenGraphBuffer = offscreenGraphBuffer;
    }
    
    private void updateState(long elapsed) {
        
        // TODO: update the NodeSprites, if any changed (went down or came up)
        
        // Update the job sprites
        List<GridEvent> events = getNextSlice(elapsed);
        for(GridEvent event : events) {
            applyEvent(event);
        }
        
        // Clean up queue
        List<String> toRemove = new ArrayList<String>();
        for(String jobIdString : queuedJobs) {
            GridJob job = state.getJobByFullId(jobIdString);
            if (job==null) {
                toRemove.add(jobIdString);
            }
        }
        for(String jobIdString : toRemove) {
            queuedJobs.remove(jobIdString);
        }

        Collections.sort(queuedJobs, new Comparator<String>() {
            @Override
            public int compare(String jid1, String jid2) {
                GridJob j1 = state.getJobByFullId(jid1);
                GridJob j2 = state.getJobByFullId(jid2);
                return j1.getSubTime().compareTo(j2.getSubTime());
            }
        });
        
        // Relocate the remaining queued job sprites
        int i = 0;
        log.trace("---------------------------------");
        for(String jobIdString : queuedJobs) {
            
            GridJob job = state.getJobByFullId(jobIdString);
            
            Collection<JobSprite> sprites = jobSpriteMap.get(jobIdString);
            if (sprites==null) {
                log.warn("No such queued job: {}",jobIdString);
            }
            else {
                if (sprites.size()>1) {
                    log.warn("More than 1 queued job sprite with id: "+jobIdString);
                }
                for(JobSprite jobSprite : sprites) {
                    if (!jobSprite.queued) {
                        log.warn("Unqueued job in queue: "+jobIdString);
                        continue;
                    }
                    jobSprite.pos = getQueuedPosition(jobIdString, i++);    
                    log.trace("  Relocated job to y: {}\t{}",jobSprite.pos.y,job);
                }
            }
        }
        
        if (queuedJobs.size()!=state.getQueuedJobs().size()) {
            log.warn("Queue size ("+queuedJobs.size()+") does not match state ("+state.getQueuedJobs().size()+")");
        }
    }
    
    private List<GridEvent> getNextSlice(long elapsed) {
        
        List<GridEvent> slice = new ArrayList<GridEvent>();
        if (elapsed<=0) return slice;
        
        long prevElapsed = totalElapsed;
        totalElapsed += elapsed;
        
        log.trace("getNextSlice, prevElapsed={}, totalElapsed={}",prevElapsed,totalElapsed);
        
        if (prevElapsed>=totalElapsed) {
            log.warn("No slice possible with (prevElapsed={}, totalElapsed={})",prevElapsed,totalElapsed);
            return slice;
        }
        
        SortedMap<Long,List<GridEvent>> eventSlice = timeline.getEvents().subMap(prevElapsed, totalElapsed);
        
        log.trace("getNextSlice, eventSlice.size={}",eventSlice.size());
        
        for(Long offset : eventSlice.keySet()) {
            List<GridEvent> events = eventSlice.get(offset);
            for(GridEvent event : events) {
                if (event.getOffset()>=totalElapsed) {
                    break;
                }
                log.trace("getNextSlice\t{}\t{}",event.getOffset(),event.getCacheKey());
                slice.add(event);
            }
        }
        
        return slice;
    }
    
    private void applyEvent(GridEvent event) {

        String fullJobId = event.getJobId();
        GridJob job = state.getJobByFullId(fullJobId);
        
        // Update the run state
        state.applyEvent(event);
        
        // Update the sprite state
        switch(event.getType()) {
        case SUB:
            if (job==null) {
                job = new GridJob(event.getSnapshotJob());
            }
            applySub(job);  
            break;
        case START:
            if (job==null) {
                log.error("Cannot start null job");
            }
            else {
                applyStart(job);    
            }
            break;
        case END:
            if (job==null) {
                log.error("Cannot end null job");
            }
            else {
                applyEnd(job, event.getOffset());   
            }
            break;
        default:
            log.warn("Unrecognized event type: {}",event.getType());
            break;
        }
    }
    
    private void applySub(GridJob job) {

        String fullJobId = job.getFullJobId();
        Collection<JobSprite> sprites = jobSpriteMap.get(fullJobId);
        if (sprites!=null && !sprites.isEmpty()) {
            log.warn("Ignoring sub event for known job {}",fullJobId);
            return;
        }

        log.debug("Adding queued job {}",fullJobId);
        
        PVector pos = getQueuedPosition(fullJobId, queuedJobs.size());
        JobSprite jobSprite = createJobSprite(job, pos);
        jobSprite.opacity = 0;
        jobSprite.queued = true;
        addJobSprite(fullJobId,jobSprite);
        queuedJobs.add(fullJobId);
        
        if (tweenChanges) {
            Tween tween = new Tween("queue_job_"+fullJobId,getTweenDuration(DURATION_JOB_SUB))
                .addProperty(new NumberProperty(jobSprite, "opacity", 255))
                .call(jobSprite, "jobQueued")
                .noAutoUpdate(); 
            jobSprite.tweens.add(tween);
        }
        else {
            jobSprite.opacity = 255;
        }
    }
    
    private void applyStart(GridJob job) {

        String fullJobId = job.getFullJobId();
        Collection<JobSprite> sprites = jobSpriteMap.get(fullJobId);
        if (sprites.isEmpty()) {
            log.warn("Starting job that was never subbed: {}",fullJobId);
            JobSprite jobSprite = createJobSprite(job, new PVector(0, 0));
            jobSprite.queued = true;
            addJobSprite(fullJobId, jobSprite);
            sprites = jobSpriteMap.get(fullJobId);
        }

        if (job.getNode()==null) {
            log.warn("No node for job being started: {}",fullJobId);
            return;
        }
        
        if (!queuedJobs.remove(job.getFullJobId())) {
            log.warn("Starting job that was never queued: {}",fullJobId);
        }
        else {
            log.debug("Starting queued job {} on {}",fullJobId,job.getNode().getShortName());
        }
        
        String nodeName = job.getNode().getShortName();
        NodeSprite nodeSprite = nodeSprites.get(nodeName);
        
        if (sprites.size()>1) {
            log.warn("More than one sprite for job being started: "+fullJobId);
        }
        
        JobSprite jobSprite = sprites.iterator().next();
        jobSprite.queued = false;
        
        boolean found = false;
        
        int i = 0;
        GridJob[] nodeJobs = job.getNode().getSlots();
        for(int s=0; s<nodeJobs.length; s++) {
            if (nodeJobs[s]==null) continue;
            if (!nodeJobs[s].getFullJobId().equals(fullJobId)) continue;
            found = true;
            
            SlotSprite slotSprite = nodeSprite.slots[s];
            
            if (slotStartOffsets.get(slotSprite.name)!=null) {
                log.error("Error tracking slot start for jobId={}. Something on this slot was already started: {}",nodeJobs[s].getFullJobId(),slotSprite.name);
            }

            log.debug("Starting job {} on slot: {}",job.getFullJobId(),slotSprite.name);
            slotStartOffsets.put(slotSprite.name, timeline.getOffset(nodeJobs[s].getStartTime()));
            
            if (i>0) {
                jobSprite = cloneJobSprite(fullJobId);
            }
            
            jobSprite.slotName = slotSprite.name;
            
            if (tweenChanges) {
                // scale duration to the distance that needs to be traveled
                float distance = jobSprite.pos.dist(slotSprite.pos);
                float duration = (DURATION_JOB_START * distance / DISTANCE_JOB_START) * 0.6f;
                
                Tween tween = new Tween("start_job_"+fullJobId+"#"+i,getTweenDuration(duration))
                    .addPVector(jobSprite.pos, slotSprite.pos.get())
                    .call(jobSprite, "jobStarted")
                    .setEasing(Tween.SINE_BOTH)
                    .noAutoUpdate(); 

                if (isDrawLaserTracking) {
                    jobSprite.endPos = slotSprite.pos.get();
                }
                jobSprite.tweens.add(tween);
            }
            else {
                jobSprite.pos.set(slotSprite.pos);
            }
            
            i++;
        }
        
        if (!found) {
            log.warn("Could not find assigned slot for job {}, deleting it.",fullJobId);
            jobSpriteMap.removeAll(fullJobId);
        }
    }
    
    private void applyEnd(GridJob job, long endOffset) {

        String fullJobId = job.getFullJobId();
        Collection<JobSprite> sprites = jobSpriteMap.get(fullJobId);
        if (sprites==null || sprites.isEmpty()) {
            log.warn("Cannot end job that does not exist: {}",fullJobId);
            return;
        }
        
        if (job.getNode()==null) {
            log.debug("Finishing queued job {}",fullJobId);
        }
        else {
            log.debug("Finishing job {} on node {}",fullJobId, job.getNode().getShortName());
        }

        int i = 0;
        for(JobSprite jobSprite : sprites) {

            if (jobSprite.slotName!=null) {
                Long startOffset = slotStartOffsets.get(jobSprite.slotName);
                if (startOffset==null) {
                    log.error("Error tracking slot end for jobId: {}. No start offset for slot: {}",fullJobId,jobSprite.slotName);
                }
                else {
                    long timeInUse = endOffset - startOffset;
                    Long currUse = slotUsage.get(jobSprite.slotName);
                    if (currUse==null) currUse = 0L;
                    if (timeInUse>0) {
                        slotUsage.put(jobSprite.slotName, currUse+timeInUse);
                    }
                    slotStartOffsets.remove(jobSprite.slotName);
                    log.debug("Removing job from slot: {}",jobSprite.slotName);
                }
            }
            
            PVector endPos = new PVector(jobSprite.pos.x, jobSprite.pos.y - 20);
            
            if (tweenChanges) {
                Tween tween = new Tween("end_job_"+fullJobId+"#"+i,getTweenDuration(DURATION_JOB_END))
                    .addPVector(jobSprite.pos, endPos)
                    .call(jobSprite, "jobEnded")
                    .addProperty(new NumberProperty(jobSprite, "opacity", 0))
                    .noAutoUpdate(); 
    
                jobSprite.tweens.add(tween);
            }
            else {
                jobSprite.jobEnded();
            }
            
            i++;
        }
    }
    
    private NodeSprite createNodeSprite(PVector pos, GridNode node) {
        NodeSprite nodeSprite = new NodeSprite(pos, node);
        return nodeSprite;
    }
    
    private JobSprite createJobSprite(GridJob job, PVector pos) {
        JobSprite jobSprite = new JobSprite(pos, job.getOwner());
        jobSprite.color = jobSprite.borderColor = legend.getItemColor(job.getOwner());
        jobSprite.borderColor = Utils.color("FFFFFF");
        jobSprite.name = job.getFullJobId();
        jobSprite.tooltip = "Job #"+job.getFullJobId()+" for "+job.getOwner()+" ("+job.getSlots()+" slots)";
        return jobSprite;
    }

    private void addJobSprite(String fullJobId, JobSprite jobSprite) {
        jobSpriteMap.put(fullJobId, jobSprite);
    }
    
    private void removeJobSprite(String fullJobId, JobSprite jobSprite) {
        jobSpriteMap.remove(fullJobId, jobSprite);
    }
    
    private JobSprite cloneJobSprite(String fullJobId) {
        Collection<JobSprite> sprites = jobSpriteMap.get(fullJobId);
        if (sprites==null || sprites.isEmpty()) {
            log.error("Cannot expand sprites for non-existing job: "+fullJobId);
            return null;
        }
        
        JobSprite jobSprite = sprites.iterator().next(); // first sprite
        GridJob job = state.getJobByFullId(fullJobId);
        
        if (job==null) {
            log.error("Cannot expand sprites for unknown job: "+fullJobId);
            return null;
        }
        
        JobSprite copy = jobSprite.copy();
        copy.name += "#"+sprites.size();
        jobSpriteMap.put(fullJobId, copy);
        return copy;
    }
    
    private float getTweenDuration(float durationJobSub) {
        if (playSpeed<=1) return durationJobSub;
        return durationJobSub/(float)Math.log(playSpeed);
    }
    
    private void updateOffscreenBuffer() {

        offscreenBuffer.beginDraw();
        offscreenBuffer.background(colorScheme.gridBackgroundColor);
        
        for(NodeSprite nodeSprite : nodeSprites.values()) {
            nodeSprite.draw(offscreenBuffer);
        }
        
        if (isHeatmap) {
            
            // Draw heatmap color legend in the middle spot
            float barWidth = width * .5f;
            float barHeight = height * .02f;
            float barX = (width - barWidth)/2;
            float barY = queueRect.getBounds().minY + (queueRect.getHeight() - barHeight)/2;
            
            Rectangle barRect = new Rectangle(barX, barY, barWidth, barHeight);
            Bounds b = barRect.getBounds();
            for(float x=b.minX; x<=b.maxX; x++) {
                offscreenBuffer.colorMode(PApplet.HSB, 360, 100, 100);
                float hue = PApplet.map(x, b.minX, b.maxX, HEATMAP_MAX_HUE, HEATMAP_MIN_HUE);
                int color = offscreenBuffer.color(hue, HEATMAP_BRIGHTNESS, HEATMAP_SATURATION);
                Utils.stroke(offscreenBuffer, color, 255);
                Utils.fill(offscreenBuffer, color, 255);
                offscreenBuffer.colorMode(PApplet.RGB);
                offscreenBuffer.line(x, b.minY, x, b.maxY);
            }        
            

            offscreenBuffer.textFont(legendFont);
            Utils.stroke(offscreenBuffer, colorScheme.titleFontColor);
            Utils.fill(offscreenBuffer, colorScheme.titleFontColor);
            offscreenBuffer.textAlign(PApplet.LEFT, PApplet.TOP);
            offscreenBuffer.text("0%", b.minX, b.maxY+5);
            offscreenBuffer.textAlign(PApplet.RIGHT, PApplet.TOP);
            offscreenBuffer.text("100%", b.maxX, b.maxY+5);
            offscreenBuffer.textAlign(PApplet.CENTER, PApplet.TOP);
            offscreenBuffer.text("Percent Of Time In Use", width/2, b.maxY+5);
            
        }
        else {
//          List<JobSprite> sprites = new ArrayList<JobSprite>(getJobSprites().values());
//          Collections.sort(sprites, new Comparator<JobSprite>() {
//              @Override
//              public int compare(JobSprite o1, JobSprite o2) {
//                  Integer y1 = Math.round(o1.pos.y);
//                  Integer y2 = Math.round(o2.pos.y);
//                  if (y1==y2) {
//                      Integer x1 = Math.round(o1.pos.x);
//                      Integer x2 = Math.round(o2.pos.x);
//                      return x1.compareTo(x2);
//                  }
//                  return y1.compareTo(y2);
//              }
//          });

            // Draw jobs
            Iterator<JobSprite> i = getJobSprites().values().iterator();
            while (i.hasNext()) {
                Sprite sprite = i.next();
                if (!sprite.isStatic()) continue;

                sprite.draw(offscreenBuffer);
                sprite.update();
            }
            
            if (summaryMode) {
                // Draw summary view
                summaryView.draw(offscreenBuffer);
            }
            else {
                // Draw legend
                legend.draw(offscreenBuffer);
            }
        }
        
        // Draw horizontal rules
        Utils.stroke(offscreenBuffer, colorScheme.panelBorderColor);
        float gridRuleY = gridRect.getBounds().maxY+rectSpacing;
        float graphRuleY = legendRect.getBounds().maxY;
        offscreenBuffer.strokeWeight(3);
        offscreenBuffer.line(0, gridRuleY, width, gridRuleY);
        offscreenBuffer.line(0, graphRuleY, width, graphRuleY);

        // Draw title
        offscreenBuffer.textAlign(PApplet.CENTER, PApplet.TOP);
        offscreenBuffer.textFont(titleFont);
        Utils.fill(offscreenBuffer, colorScheme.titleFontColor);    
        offscreenBuffer.text(title, width/2, 10);
        
        // Draw graph window
        drawGraphWindow(offscreenBuffer);
        
        // Draw outlines
        if (isDrawOutlines) {
            offscreenBuffer.noFill();
            offscreenBuffer.strokeWeight(1);
            Utils.stroke(offscreenBuffer, outlineColor);
            gridRect.draw(offscreenBuffer);
            queueRect.draw(offscreenBuffer);
            legendRect.draw(offscreenBuffer);
            graphRect.draw(offscreenBuffer);
        }
        
        offscreenBuffer.endDraw();
    }
    
    private void updateOffscreenGraphBuffer() {

        offscreenGraphBuffer.beginDraw();

        offscreenGraphBuffer.background(0, 0);

        if (isDrawSnapshotLines) {
            offscreenGraphBuffer.strokeWeight(1);
            Utils.stroke(offscreenGraphBuffer, colorScheme.gridBaseColor);
            for(Snapshot snapshot : timeline.getSnapshots()) {
                long offset = timeline.getOffset(snapshot.getSamplingTime());
                float lineX = PApplet.map(offset, timeline.getFirstOffset(), timeline.getLastOffset(), 0, graphBodyRect.getWidth()-1);
                offscreenGraphBuffer.line(lineX, 0, lineX, graphBodyRect.getHeight());
            }
        }
        
        if (queuedJobsGraph!=null) queuedJobsGraph.draw(offscreenGraphBuffer);
        if (runningJobsGraph!=null) runningJobsGraph.draw(offscreenGraphBuffer);

        offscreenGraphBuffer.endDraw();
    }
    
    private void flipBuffers() {
        PGraphics temp = onscreenBuffer;
        onscreenBuffer = offscreenBuffer;
        offscreenBuffer = temp;
        temp = onscreenGraphBuffer;
        onscreenGraphBuffer = offscreenGraphBuffer;
        offscreenGraphBuffer = temp;
    }

    public void drawGraphWindow(PGraphics buf) {
        
        if (queuedJobsGraph==null && runningJobsGraph==null) return;
        
        buf.beginDraw();        
        
        // Graph legend
        
        float x = graphBodyRect.getPos().x+1;
        float y = graphRect.getPos().y+1;
        
        buf.textFont(legendFont);
        buf.textAlign(PApplet.LEFT, PApplet.CENTER);
        
        Utils.stroke(buf, runningJobsGraph.getColor());
        Utils.fill(buf, runningJobsGraph.getColor());
        buf.rect(x, y, slotWidth, slotHeight);
        
        x += slotWidth+5;
        
        buf.text("Running Jobs", x, y+slotHeight/2);

        x += buf.textWidth("Running Jobs") + padding;
        Utils.stroke(buf, queuedJobsGraph.getColor());
        Utils.fill(buf, queuedJobsGraph.getColor());
        buf.rect(x, y, slotWidth, slotHeight);
        
        x += slotWidth+5;
        buf.text("Queued Jobs", x, y+slotHeight/2);
        
        // Time labels
        
        buf.textFont(legendFont);
        Utils.stroke(buf, colorScheme.titleFontColor);
        Utils.fill(buf, colorScheme.titleFontColor);

        Bounds b = graphBodyRect.getBounds();
                
        List<Snapshot> snapshots = timeline.getSnapshots();
        
        float minDistance = graphBodyRect.getWidth() * 0.05f;
        float lastX = Float.MIN_VALUE;
        
        long first = timeline.getFirstOffset();
        long last = timeline.getLastOffset();
        
        DateTime baseline = new DateTime(timeline.getBaselineDate().getTime());
        DateTime date = new DateTime(baseline);
        date = date.withMinuteOfHour(0).withMillis(0);

        int i =0;
        
        while (true) {
            date = date.plusMinutes(15);
            
            if (date.isBefore(baseline)) continue;
            
            Interval interval = new Interval(baseline, date);
            Duration duration = new Duration(interval);
            long offset = duration.getMillis();
            
            if (offset<first) continue;
            if (offset>last) break;

            float lineX = PApplet.map(offset, first, last, graphBodyRect.getPos().x, b.maxX);

            if (lineX-lastX<minDistance) {
                continue;
            }
            lastX = lineX;
            
            if (i==0) {
                buf.textAlign(PApplet.LEFT, PApplet.TOP);
            }
            else if (i==snapshots.size()-1) {
                buf.textAlign(PApplet.RIGHT, PApplet.TOP);
            }
            else {
                buf.textAlign(PApplet.CENTER, PApplet.TOP);
            }
            
            buf.text(df.print(date), lineX, graphBodyRect.getBounds().maxY+timeLabelOffset);
        
            i++;
        }
        
        Snapshot lastSnapshot = snapshots.get(snapshots.size()-1);
        String dateStr = dfDate.print(new DateTime(lastSnapshot.getSamplingTime()));
        buf.textAlign(PApplet.RIGHT, PApplet.TOP);
        buf.text(dateStr, b.maxX, graphRect.getPos().y+1);
        
        buf.endDraw();
    }
    
    public class NodeSprite extends Sprite {

        protected SlotSprite[] slots = new SlotSprite[GridState.SLOTS_PER_NODE];
        protected JobSprite[] jobs = new JobSprite[GridState.SLOTS_PER_NODE];
 
        NodeSprite(PVector pos, GridNode node) {
            super(pos);
            this.name = node.getShortName();
            this.tooltip = "Node "+name;
            
            for (int s = 0; s < GridState.SLOTS_PER_NODE; s++) {
                float sx, sy, i;
                if (s < 4) {
                    i = s;
                    sy = slotSpacing;
                } else {
                    i = s - 4;
                    sy = 2 * slotSpacing + slotHeight;
                }
                sx = i * (slotWidth + slotSpacing) + slotSpacing;
                slots[s] = new SlotSprite(new PVector(pos.x + sx, pos.y + nodeLabelHeight + sy));
                slots[s].name = name+"/"+s;
                slots[s].tooltip = tooltip+", Slot #"+s;
            }
        }

        public void draw(PGraphics buf) {
            buf.strokeWeight(1);
            Utils.fill(buf, colorScheme.nodeBackgroundColor, opacity);
            Utils.stroke(buf, colorScheme.nodeBorderColor, opacity);
              
            buf.rect(pos.x, pos.y, nodeWidth, nodeHeight);
            
            for (int s = 0; s < GridState.SLOTS_PER_NODE; s++) {
                slots[s].draw(buf);
            }

            Utils.fill(buf, colorScheme.nodeFontColor, opacity);
            
            buf.textFont(nodeFont);
            buf.textAlign(PApplet.CENTER, PApplet.CENTER);
            float x = pos.x + nodeWidth/2;
            float y = pos.y + nodeLabelHeight/2;
            buf.text(name, (int)x, (int)y);
        }
        
        public void draw() { }
    }

    public class SlotSprite extends Sprite {

        SlotSprite(PVector pos) {
            super(pos);
        }

        public void draw(PGraphics buf) {
            
            if (isHeatmap) {
                Long usage = slotUsage.get(name);
                int slotColor = buf.color(0, 100, 100);
                if (usage==null) usage = 0L;
                
                Long startOffset = slotStartOffsets.get(name);
                if (startOffset!=null) {
                    usage += totalElapsed-startOffset;
                }
                
                long total = totalElapsed - nextStartingPosition;
                float percentInUse = (float)usage/(float)total;
                if (percentInUse<0 || percentInUse>1) {
                    log.warn("Usage percent for slot {} is out of bounds: {}",name,percentInUse);
                    percentInUse = PApplet.constrain(percentInUse, 0, 1);
                }

                buf.colorMode(PApplet.HSB, 360, 100, 100);
                float hue = PApplet.map(percentInUse, 0, 1, HEATMAP_MAX_HUE, HEATMAP_MIN_HUE);
                slotColor = buf.color(hue, HEATMAP_BRIGHTNESS, HEATMAP_SATURATION);
                Utils.stroke(buf, slotColor, opacity);
                Utils.fill(buf, slotColor, opacity);
                buf.colorMode(PApplet.RGB);
            }
            else {
                Utils.stroke(buf, colorScheme.emptySlotColor, opacity);
                Utils.fill(buf, colorScheme.emptySlotColor, opacity);   
            }
            buf.strokeWeight(1);
            buf.rect(pos.x, pos.y, slotWidth, slotHeight);
        }
    }
    
    public class JobSprite extends Sprite {

        protected String username;
        protected int color;
        protected int borderColor;
        protected boolean queued = false;
        protected boolean defunct = false;
        protected PVector endPos; // used for laser tracking
        protected String slotName; // used for heatmap usage tracking
        
        JobSprite(PVector pos, String username) {
            super(pos);
            this.username = username;
        }

        public void draw(PGraphics buf) {           
            
            buf.strokeWeight(1);

            if (endPos!=null && isDrawLaserTracking) {
                Utils.stroke(buf, laserColor);
                float ox = slotWidth/2;
                float oy = slotHeight/2;
                buf.line(pos.x+ox, pos.y+oy, endPos.x+ox, endPos.y+oy);
            }
            
            if (bwMode && !username.equals(legend.getHighlightUsername())) {
                buf.colorMode(PApplet.HSB, 360, 100, 100);
                int bwColor = buf.color(buf.hue(color), 0, 50);
                buf.stroke(bwColor, opacity);
                buf.fill(bwColor, opacity);
                buf.colorMode(PApplet.RGB);
            }
            else {
                buf.stroke(borderColor, opacity);
                buf.fill(color, opacity);
            }
            
            if (queued) {
                buf.rect(pos.x, pos.y, queuedJobWidth, queuedJobHeight);
            }
            else {
                buf.rect(pos.x, pos.y, slotWidth, slotHeight);
            }
        }

        public JobSprite copy() {
            JobSprite copy = new JobSprite(pos, username);
            copy.name = name;
            copy.color = this.color;
            copy.borderColor = this.borderColor;
            copy.queued = this.queued;
            return copy;
        }

        public void jobQueued() {
        }
        
        public void jobStarted() {
            this.endPos = null;
        }
        
        public void jobEnded() {
            defunct = true;
            opacity = 0; // just in case
        }

        public String getUsername() {
            return username;
        }
    }

    public PImage getMainBuffer() {
        return onscreenBuffer;
    }

    public PImage getGraphBuffer() {
        return onscreenGraphBuffer;
    }
    
    public Rectangle getGraphRect() {
        return graphBodyRect;
    }

    public Map<String,Integer> getSlotsUsedByUser() {
        return ImmutableMap.copyOf(state.getSlotsUsedByUser());
    }
    
    public Integer getColorForUser(String username) {
        return legend.getColorAssignments().get(username);
    }
    
    public List<String> getUsers() {
        return ImmutableList.copyOf(legend.getColorAssignments().keySet());
    }

    public void setColorScheme(ColorScheme colorScheme) {
        this.colorScheme = colorScheme;
        this.runningJobsGraph.setColor(colorScheme.graphLineColorRunningJobs); 
        this.queuedJobsGraph.setColor(colorScheme.graphLineColorQueuedJobs);
    }
    
    public ColorScheme getColorScheme() {
        return colorScheme;
    }

    public void setBwMode(boolean bwMode, String username) {
        if (bwMode!=this.bwMode || (bwMode && !legend.getHighlightUsername().equals(username))) {
            setColorScheme(bwMode ? new GrayColorScheme() : new DefaultColorScheme());
            this.bwMode = bwMode;
            legend.setHighlightUsername(username);
        }
    }
    
    public boolean isSummaryMode() {
        return summaryMode;
    }

    public void setSummaryMode(boolean summaryMode) {
        this.summaryMode = summaryMode;
    }

    public Multimap<String, JobSprite> getJobSprites() {
        synchronized (jobSpriteMap) {
            return ImmutableMultimap.copyOf(jobSpriteMap);
        }
    }

    public void setDrawLaserTracking(boolean isDrawLaserTracking) {
        this.isDrawLaserTracking = isDrawLaserTracking;
    }

    public void setDrawOutlines(boolean isDrawOutlines) {
        this.isDrawOutlines = isDrawOutlines;
    }

    public void setDrawSnapshotLines(boolean isDrawSnapshotLines) {
        this.isDrawSnapshotLines = isDrawSnapshotLines;
    }
    
    public void setAnonUsernames(boolean isAnonUsernames) {
        legend.setAnonUsernames(isAnonUsernames);
    }
    
    public boolean isHeatmap() {
        return isHeatmap;
    }

    public void setHeatmap(boolean isHeatmap) {
        if (isHeatmap!=this.isHeatmap) {
            setColorScheme(isHeatmap ? new HeatmapColorScheme() : new DefaultColorScheme());
            this.isHeatmap = isHeatmap;
        }
    }
    
    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }
}