package com.tvnav.netflixremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import android.content.Context;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class NavService extends AccessibilityService {
    private static final String TAG="TvNav", NETFLIX="com.netflix.mediaclient";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager wm; private Highlight overlay; private WindowManager.LayoutParams overlayParams;
    private Rect selectedRect; private String selectedId, selectedText; private boolean enabled=true, centerLongFired, centerDown;
    private Runnable centerLong;
    private boolean centerStartedEnabled, centerSelectOnly, centerWasSkip;
    private String currentActivity = "";
    private boolean inPlayer, controlsVisible, selectedSkip, playerActionScheduled;
    private int playerWindowId = -1, seekingKey = KeyEvent.KEYCODE_UNKNOWN;
    private long lastSeekRequestAt = -300, lastSeekClickAt = -300;
    private Rect detectedSkipRect;
    private String detectedSkipText;
    private final ArrayDeque<PlayerAction> pendingPlayerActions = new ArrayDeque<PlayerAction>();
    private final Runnable playerActionReady = new Runnable() {
        @Override public void run() { drainPlayerActions(); }
    };
    private enum PlayerAction { PLAY_PAUSE, REWIND, FORWARD, SELECT_PLAY_PAUSE }
    private static final class PlayerState {
        final List<Candidate> nodes = new ArrayList<Candidate>();
        boolean netflixActive, playerMarker, controlsVisible;
        int windowId = -1;
        Candidate skip;
    }
    private static volatile NavService instance;
    private static final class Candidate { AccessibilityNodeInfo node; Rect r; String id,text; boolean clickable; Candidate(AccessibilityNodeInfo n){node=n;r=new Rect();n.getBoundsInScreen(r);id=n.getViewIdResourceName();CharSequence t=n.getText();if(t==null)t=n.getContentDescription();text=t==null?"":t.toString();clickable=n.isClickable()||n.isLongClickable();} }
    @Override public void onCreate(){super.onCreate();instance=this;wm=(WindowManager)getSystemService(WINDOW_SERVICE);overlay=new Highlight(this);overlayParams=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);overlayParams.gravity=android.view.Gravity.TOP|android.view.Gravity.LEFT;}
    public static boolean isEnabled(Context c){String enabledServices=android.provider.Settings.Secure.getString(c.getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);String component=c.getPackageName()+"/"+NavService.class.getName();if(enabledServices==null||android.provider.Settings.Secure.getInt(c.getContentResolver(),android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,0)!=1)return false;for(String item:enabledServices.split(":"))if(component.equalsIgnoreCase(item))return true;return false;}
    @Override protected void onServiceConnected(){super.onServiceConnected();Log.i(TAG,"Accessibility service connected");}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        boolean fromNetflix = event.getPackageName() != null
                && NETFLIX.contentEquals(event.getPackageName());
        int type = event.getEventType();
        if (!fromNetflix) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isNetflixActive()) {
                cancelCenterPress();
                cancelPlayerActions();
                currentActivity = "";
                inPlayer = false;
                controlsVisible = false;
                playerWindowId = -1;
                clearSelection();
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String activity = event.getClassName() == null ? "" : event.getClassName().toString();
            if (!activity.equals(currentActivity)) cancelPlayerActions();
            currentActivity = activity;
            clearSelection();
            if (isNetflixActive()) {
                refreshPlayerState();
                handler.postDelayed(new Runnable() {
                    @Override public void run() { selectFirst(); }
                }, 350);
            } else {
                cancelPlayerActions();
                hideOverlay();
            }
        } else if (isNetflixActive() && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            refreshPlayerState();
            if (!inPlayer || controlsVisible) refreshSelection();
        }
    }
    @Override public void onInterrupt() {
        cancelCenterPress();
        cancelPlayerActions();
        clearSelection();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
        instance = null;
        super.onDestroy();
    }
    private boolean isNetflixActive(){AccessibilityNodeInfo n=null;try{n=getRootInActiveWindow();return n!=null&&NETFLIX.contentEquals(n.getPackageName());}catch(Exception x){return false;}finally{if(n!=null)n.recycle();}}
    @Override protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        int action = event.getAction();
        Log.d(TAG, "key=" + KeyEvent.keyCodeToString(key) + " action=" + action
                + " repeat=" + event.getRepeatCount());
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_INFO) {
            if (!isNetflixActive()) return false;
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                cancelCenterPress();
                toggle();
            }
            return true;
        }
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!arrow && !ok) return false;
        if (!isNetflixActive()) {
            cancelCenterPress();
            cancelPlayerActions();
            clearSelection();
            return false;
        }
        if (isKeyboardVisible()) {
            cancelCenterPress();
            cancelPlayerActions();
            hideOverlay();
            Log.d(TAG, "keyboard open: pass through");
            return false;
        }
        if (ok) return handleOk(event);
        if (action == KeyEvent.ACTION_UP && seekingKey == key) seekingKey = KeyEvent.KEYCODE_UNKNOWN;
        if (!enabled) return false;
        if (action == KeyEvent.ACTION_DOWN) {
            PlayerState state = readPlayerState();
            try {
                if (inPlayer) handlePlayerArrow(event, state);
                else navigate(key);
            } finally { release(state.nodes); }
        }
        return true;
    }
    private boolean handleOk(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!centerDown) {
                centerDown = true;
                centerLongFired = false;
                centerStartedEnabled = enabled;
                PlayerState state = readPlayerState();
                try {
                    centerWasSkip = selectedSkip;
                    centerSelectOnly = enabled && inPlayer && controlsVisible && selectedRect == null;
                    if (centerSelectOnly) selectPlayPause(state);
                } finally { release(state.nodes); }
                centerLong = new Runnable() {
                    @Override public void run() {
                        if (!centerDown || !isNetflixActive() || isKeyboardVisible()) return;
                        centerLongFired = true;
                        toggle();
                    }
                };
                handler.postDelayed(centerLong, 800);
            }
            return centerStartedEnabled || centerLongFired;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (centerLong != null) handler.removeCallbacks(centerLong);
            boolean consume = centerStartedEnabled || centerLongFired;
            boolean click = centerDown && centerStartedEnabled && enabled
                    && !centerLongFired && !centerSelectOnly && !event.isCanceled();
            boolean wasSkip = centerWasSkip;
            cancelCenterPress();
            if (click) {
                refreshPlayerState();
                if (!wasSkip || (inPlayer && selectedSkip)) clickSelected();
            }
            return consume;
        }
        return centerStartedEnabled || centerLongFired;
    }
    private void cancelCenterPress() {
        if (centerLong != null) handler.removeCallbacks(centerLong);
        centerLong = null;
        centerDown = false;
        centerLongFired = false;
        centerStartedEnabled = false;
        centerSelectOnly = false;
        centerWasSkip = false;
    }
    private boolean isKeyboardVisible(){try{List<AccessibilityWindowInfo> ws=getWindows();if(ws!=null)for(AccessibilityWindowInfo w:ws)if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)return true;}catch(Exception ignored){}return false;}
    private void toggle() {
        enabled = !enabled;
        cancelPlayerActions();
        clearSelection();
        if (enabled && isNetflixActive()) selectFirst();
        Toast.makeText(this, "Remote helper: " + (enabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
        Log.i(TAG, "helper=" + enabled);
    }
    private static String normalized(CharSequence value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT)
                .replace('\u0131', 'i').replace("\u0307", "");
    }
    private static boolean isPlayPauseDescription(String description) {
        if (description.contains("sonraki") || description.contains("next")
                || description.contains("skip") || description.contains("atla")) return false;
        for (String word : description.split("[^\\p{L}\\p{N}]+")) {
            if (word.equals("duraklat") || word.equals("oynat")
                    || word.equals("pause") || word.equals("play")) return true;
        }
        return false;
    }
    private static boolean isSkipLabel(CharSequence label) {
        String text = normalized(label);
        return text.contains("atla") || text.contains("skip")
                || text.contains("sonraki bölüm") || text.contains("next episode");
    }
    private void collectPlayerNodes(AccessibilityNodeInfo node, PlayerState state, int depth) {
        if (node == null || depth > 45) return;
        try {
            String description = normalized(node.getContentDescription());
            if (description.equals("oynatici kontrollerini göster")) state.playerMarker = true;
            if (node.isVisibleToUser()) {
                if (isPlayPauseDescription(description) || description.contains("10 saniye")
                        || description.contains("seconds")) state.controlsVisible = true;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && bounds.left >= 0 && bounds.top >= 0
                        && bounds.right <= getResources().getDisplayMetrics().widthPixels
                        && bounds.bottom <= getResources().getDisplayMetrics().heightPixels) {
                    state.nodes.add(new Candidate(AccessibilityNodeInfo.obtain(node)));
                }
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                try { collectPlayerNodes(child, state, depth + 1); }
                finally { if (child != null) child.recycle(); }
            }
        } catch (Exception exception) { Log.w(TAG, "player node scan failed", exception); }
    }
    private PlayerState readPlayerState() {
        PlayerState state = new PlayerState();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            state.netflixActive = root != null && NETFLIX.contentEquals(root.getPackageName());
            if (state.netflixActive) {
                state.windowId = root.getWindowId();
                collectPlayerNodes(root, state, 0);
            }
        } catch (Exception exception) { Log.w(TAG, "player state scan failed", exception); }
        finally { if (root != null) root.recycle(); }
        boolean player = state.netflixActive
                && (currentActivity.contains("PlayerActivity") || state.playerMarker);
        if (player != inPlayer || (player && state.windowId != playerWindowId)) {
            cancelPlayerActions();
            clearSelection();
        }
        if (player != inPlayer || (player && controlsVisible != state.controlsVisible)) {
            Log.i(TAG, "player=" + player + " controlsVisible=" + state.controlsVisible
                    + " activity=" + currentActivity);
        }
        inPlayer = player;
        controlsVisible = player && state.controlsVisible;
        playerWindowId = player ? state.windowId : -1;
        if (!player) {
            detectedSkipRect = null;
            detectedSkipText = null;
            return state;
        }
        for (Candidate candidate : state.nodes) {
            if (candidate.node.isClickable() && candidate.node.isEnabled()
                    && !(controlsVisible && candidate.r.top >= 640)
                    && (isSkipLabel(candidate.node.getContentDescription())
                    || isSkipLabel(candidate.node.getText()))) {
                state.skip = candidate;
                break;
            }
        }
        if (state.skip != null) {
            String label = labelOf(state.skip);
            if (!state.skip.r.equals(detectedSkipRect) || !label.equals(detectedSkipText)) {
                Log.i(TAG, "skip button detected: " + label + " rect=" + state.skip.r);
            }
            detectedSkipRect = new Rect(state.skip.r);
            detectedSkipText = label;
        } else {
            if (detectedSkipRect != null) Log.i(TAG, "skip button disappeared");
            detectedSkipRect = null;
            detectedSkipText = null;
        }
        if (!controlsVisible) {
            if (state.skip != null && enabled && !isKeyboardVisible()) {
                cancelPlayerActions();
                setSelection(state.skip);
                selectedSkip = true;
            } else clearSelection();
        } else if (selectedSkip) clearSelection();
        return state;
    }
    private void refreshPlayerState() {
        PlayerState state = readPlayerState();
        release(state.nodes);
    }
    private String labelOf(Candidate candidate) {
        CharSequence text = candidate.node.getText();
        if (!nonempty(text)) text = candidate.node.getContentDescription();
        return text == null ? "" : text.toString();
    }
    private Candidate findControl(PlayerState state, PlayerAction action) {
        for (Candidate candidate : state.nodes) {
            if (!candidate.node.isEnabled()) continue;
            String description = normalized(candidate.node.getContentDescription());
            boolean matches;
            if (action == PlayerAction.REWIND) {
                matches = description.contains("10 saniye geri") || description.contains("rewind")
                        || description.contains("back 10");
            } else if (action == PlayerAction.FORWARD) {
                matches = description.contains("10 saniye ileri") || description.contains("forward")
                        || description.contains("skip ahead");
            } else matches = isPlayPauseDescription(description);
            if (matches) return candidate;
        }
        return null;
    }
    private boolean nearlySameBounds(Rect first, Rect second) {
        return Math.abs(first.left - second.left) <= 4 && Math.abs(first.top - second.top) <= 4
                && Math.abs(first.right - second.right) <= 4
                && Math.abs(first.bottom - second.bottom) <= 4;
    }
    private AccessibilityNodeInfo clickableControl(Candidate control, List<Candidate> nodes) {
        if (control.node.isClickable() && control.node.isEnabled()) {
            return AccessibilityNodeInfo.obtain(control.node);
        }
        for (Candidate candidate : nodes) {
            if (candidate.node.isClickable() && candidate.node.isEnabled()
                    && nearlySameBounds(control.r, candidate.r)) {
                return AccessibilityNodeInfo.obtain(candidate.node);
            }
        }
        AccessibilityNodeInfo parent = control.node.getParent();
        for (int depth = 0; parent != null && depth < 45; depth++) {
            if (parent.isVisibleToUser() && parent.isEnabled() && parent.isClickable()) return parent;
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        if (parent != null) parent.recycle();
        return null;
    }
    private boolean clickControl(Candidate control, List<Candidate> nodes, String reason, Rect tapBounds) {
        AccessibilityNodeInfo target = null;
        try {
            target = clickableControl(control, nodes);
            Log.d(TAG, "control resolved: " + reason + " label=" + labelOf(control)
                    + " rect=" + control.r + " clickable=" + (target != null));
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            Log.d(TAG, "control gesture fallback: " + reason + " rect=" + tapBounds);
            tap(tapBounds.centerX(), tapBounds.centerY(), 50);
            return true;
        } catch (Exception exception) {
            Log.w(TAG, "control click failed: " + reason, exception);
            return false;
        } finally { if (target != null) target.recycle(); }
    }
    private void selectPlayPause(PlayerState state) {
        Candidate control = findControl(state, PlayerAction.PLAY_PAUSE);
        if (control == null) {
            clearSelection();
            Log.d(TAG, "play/pause control unavailable; nothing selected");
        } else setSelection(control);
    }
    private void handlePlayerArrow(KeyEvent event, PlayerState state) {
        int key = event.getKeyCode();
        boolean horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (horizontal && seekingKey == key) {
            requestSeek(key, state);
        } else if (!controlsVisible && state.skip == null) {
            if (horizontal) {
                seekingKey = key;
                requestSeek(key, state);
            } else queuePlayerAction(PlayerAction.SELECT_PLAY_PAUSE, state);
        } else if (controlsVisible && selectedRect == null) selectPlayPause(state);
        else navigate(key);
    }
    private void requestSeek(int key, PlayerState state) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSeekRequestAt < 300) return;
        lastSeekRequestAt = now;
        queuePlayerAction(key == KeyEvent.KEYCODE_DPAD_LEFT
                ? PlayerAction.REWIND : PlayerAction.FORWARD, state);
    }
    private void cancelPlayerActions() {
        handler.removeCallbacks(playerActionReady);
        pendingPlayerActions.clear();
        playerActionScheduled = false;
        seekingKey = KeyEvent.KEYCODE_UNKNOWN;
        lastSeekRequestAt = -300;
        lastSeekClickAt = -300;
    }
    private void queuePlayerAction(PlayerAction action, PlayerState state) {
        if (!enabled || !inPlayer || isKeyboardVisible() || (!controlsVisible && state.skip != null)) return;
        if (action == PlayerAction.SELECT_PLAY_PAUSE && pendingPlayerActions.contains(action)) return;
        pendingPlayerActions.add(action);
        if (playerActionScheduled) return;
        playerActionScheduled = true;
        if (!controlsVisible) showControls(state);
        else handler.post(playerActionReady);
    }
    private void showControls(PlayerState state) {
        if (!inPlayer || controlsVisible) {
            handler.post(playerActionReady);
            return;
        }
        for (Candidate candidate : state.nodes) {
            if (candidate.node.isClickable() && candidate.node.isEnabled() && candidate.r.contains(640, 520)) {
                Log.w(TAG, "safe controls spot occupied; not tapping a button");
                cancelPlayerActions();
                return;
            }
        }
        Log.d(TAG, "showControls: safe tap (640,520), rescan in 350ms");
        tap(640, 520, 50);
        handler.postDelayed(playerActionReady, 350);
    }
    private void drainPlayerActions() {
        playerActionScheduled = false;
        if (pendingPlayerActions.isEmpty()) return;
        if (!enabled || !isNetflixActive() || isKeyboardVisible()) {
            cancelPlayerActions();
            return;
        }
        PlayerState state = readPlayerState();
        try {
            if (!inPlayer || !controlsVisible || pendingPlayerActions.isEmpty()) {
                Log.d(TAG, "player action canceled: controls unavailable or skip has priority");
                cancelPlayerActions();
                return;
            }
            PlayerAction action = pendingPlayerActions.peek();
            boolean seek = action == PlayerAction.REWIND || action == PlayerAction.FORWARD;
            long delay = seek ? 300 - (SystemClock.uptimeMillis() - lastSeekClickAt) : 0;
            if (delay > 0) {
                playerActionScheduled = true;
                handler.postDelayed(playerActionReady, delay);
                return;
            }
            pendingPlayerActions.remove();
            Candidate control = findControl(state, action);
            if (control == null) Log.d(TAG, "player control unavailable: " + action);
            else {
                setSelection(control);
                if (action != PlayerAction.SELECT_PLAY_PAUSE
                        && clickControl(control, state.nodes, action.toString(), control.r) && seek) {
                    lastSeekClickAt = SystemClock.uptimeMillis();
                }
            }
            if (!pendingPlayerActions.isEmpty()) {
                playerActionScheduled = true;
                handler.post(playerActionReady);
            }
        } finally { release(state.nodes); }
    }
    private void clickPlayerSelected() {
        PlayerState state = readPlayerState();
        try {
            if (!enabled || !inPlayer || isKeyboardVisible()) return;
            if (!controlsVisible && state.skip == null) {
                queuePlayerAction(PlayerAction.PLAY_PAUSE, state);
                return;
            }
            if (selectedRect == null) {
                if (controlsVisible) selectPlayPause(state);
                return;
            }
            Candidate selected = selectedSkip ? state.skip : findSelected(state.nodes);
            if (selected == null || !selected.node.isEnabled()) {
                clearSelection();
                return;
            }
            Rect bounds = new Rect(selectedRect);
            clickControl(selected, state.nodes, selectedSkip ? "SKIP" : "SELECTED", bounds);
            clearSelection();
        } finally { release(state.nodes); }
    }
    private List<Candidate> scan(){List<Candidate> out=new ArrayList<Candidate>(); AccessibilityNodeInfo root=null;try{root=getRootInActiveWindow();if(root!=null&&NETFLIX.contentEquals(root.getPackageName()))walk(root,out,0);else {if(root!=null){root.recycle();root=null;}List<AccessibilityWindowInfo> wins=getWindows();if(wins!=null)for(AccessibilityWindowInfo w:wins){AccessibilityNodeInfo r=null;try{if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){r=w.getRoot();if(r!=null&&NETFLIX.contentEquals(r.getPackageName()))walk(r,out,0);}}finally{if(r!=null)r.recycle();}}}}catch(Exception ex){Log.w(TAG,"scan failed",ex);}finally{if(root!=null)root.recycle();}
        Rect screen=new Rect(0,0,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);int max=screen.width()*screen.height()*60/100;for(int i=out.size()-1;i>=0;i--){Candidate c=out.get(i);if(c.r.isEmpty()||!screen.contains(c.r)||c.r.width()*c.r.height()>max){c.node.recycle();out.remove(i);}}
        // A clickable container (e.g. a Netflix episode row that plays the episode) may hold smaller buttons
        // (e.g. download). Keep the container, but shrink it to its largest free strip so it can be reached
        // on its own; drop it only when its children cover nearly all of it.
        int cnt=out.size();Rect[] orig=new Rect[cnt];boolean[] drop=new boolean[cnt];for(int i=0;i<cnt;i++)orig[i]=new Rect(out.get(i).r);
        for(int i=0;i<cnt;i++){Candidate p=out.get(i);if(!p.clickable)continue;Rect pr=orig[i];int pa=pr.width()*pr.height();
            int minL=Integer.MAX_VALUE,maxR=Integer.MIN_VALUE,minT=Integer.MAX_VALUE,maxB=Integer.MIN_VALUE;boolean has=false;
            for(int j=0;j<cnt;j++){if(i==j)continue;Rect cr=orig[j];if(out.get(j).clickable&&pr.contains(cr)&&cr.width()*cr.height()<pa*0.75){has=true;minL=Math.min(minL,cr.left);maxR=Math.max(maxR,cr.right);minT=Math.min(minT,cr.top);maxB=Math.max(maxB,cr.bottom);}}
            if(!has)continue;
            Rect[] strips={new Rect(pr.left,pr.top,minL,pr.bottom),new Rect(maxR,pr.top,pr.right,pr.bottom),new Rect(pr.left,pr.top,pr.right,minT),new Rect(pr.left,maxB,pr.right,pr.bottom)};
            Rect best=null;int bestA=0;for(Rect s:strips){int a2=s.width()>0&&s.height()>0?s.width()*s.height():0;if(a2>bestA){bestA=a2;best=s;}}
            if(best!=null&&bestA>=pa/4)p.r=best;else drop[i]=true;}
        for(int i=cnt-1;i>=0;i--)if(drop[i]){out.get(i).node.recycle();out.remove(i);}
        Log.d(TAG,"candidates="+out.size());return out;}
    private void walk(AccessibilityNodeInfo n,List<Candidate> out,int depth){if(n==null||depth>45)return;try{if(n.isVisibleToUser()&&(n.isClickable()||n.isLongClickable()))out.add(new Candidate(AccessibilityNodeInfo.obtain(n)));for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=null;try{c=n.getChild(i);if(c!=null)walk(c,out,depth+1);}finally{if(c!=null)c.recycle();}}}catch(Exception ignored){}}
    private boolean hasText(AccessibilityNodeInfo n){return nonempty(n.getText())||nonempty(n.getContentDescription());} private boolean nonempty(CharSequence c){return c!=null&&c.length()>0;}
    private void navigate(int key) {
        List<Candidate> candidates = scan();
        try {
            if (candidates.isEmpty()) {
                handleEmpty(key);
                return;
            }
            if (selectedRect == null) {
                if (inPlayer) {
                    PlayerState state = readPlayerState();
                    try { selectPlayPause(state); }
                    finally { release(state.nodes); }
                } else setSelection(first(candidates));
                return;
            }
            Candidate from = findSelected(candidates);
            Candidate best = null;
            double bestScore = Double.MAX_VALUE;
            for (Candidate candidate : candidates) {
                if (candidate == from) continue;
                double candidateScore = score(selectedRect, candidate.r, key);
                if (candidateScore < bestScore) {
                    bestScore = candidateScore;
                    best = candidate;
                }
            }
            if (best != null) {
                setSelection(best);
                Log.d(TAG, "chosen=" + best.text + " rect=" + best.r);
            } else if (!inPlayer) scroll(key);
        } finally { release(candidates); }
    }
    // Same rules as Android's FocusFinder: the target must really lie in the pressed direction,
    // and items in the same row/column ("beam") always beat items outside it.
    private double score(Rect a,Rect b,int key){boolean horizontal=key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;int major;
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT){if(!((a.left<b.left||a.right<=b.left)&&a.right<b.right))return Double.MAX_VALUE;major=Math.max(0,b.left-a.right);}
        else if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(!((a.right>b.right||a.left>=b.right)&&a.left>b.left))return Double.MAX_VALUE;major=Math.max(0,a.left-b.right);}
        else if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(!((a.top<b.top||a.bottom<=b.top)&&a.bottom<b.bottom))return Double.MAX_VALUE;major=Math.max(0,b.top-a.bottom);}
        else{if(!((a.bottom>b.bottom||a.top>=b.bottom)&&a.top>b.top))return Double.MAX_VALUE;major=Math.max(0,a.top-b.bottom);}
        int minor=horizontal?Math.abs(a.centerY()-b.centerY()):Math.abs(a.centerX()-b.centerX());
        boolean inBeam=horizontal?(a.top<b.bottom&&b.top<a.bottom):(a.left<b.right&&b.left<a.right);
        return (inBeam?0:1e8)+13.0*major*major+(double)minor*minor;}
    private Candidate findSelected(List<Candidate> candidates) {
        if (inPlayer) {
            for (Candidate candidate : candidates) if (candidate.r.equals(selectedRect)) return candidate;
        }
        for (Candidate candidate : candidates) {
            if (selectedId != null && selectedId.equals(candidate.id)
                    && selectedText != null && selectedText.equals(candidate.text)) return candidate;
        }
        for (Candidate candidate : candidates) if (candidate.r.equals(selectedRect)) return candidate;
        return null;
    }
    private Candidate first(List<Candidate> cs){Collections.sort(cs,new Comparator<Candidate>(){public int compare(Candidate a,Candidate b){int y=a.r.top-b.r.top;return y!=0?y:a.r.left-b.r.left;}});return cs.get(0);}
    private void selectFirst() {
        if (!enabled || !isNetflixActive() || isKeyboardVisible()) return;
        refreshPlayerState();
        if (inPlayer) return;
        List<Candidate> candidates = scan();
        try { if (!candidates.isEmpty()) setSelection(first(candidates)); }
        finally { release(candidates); }
    }
    private void setSelection(Candidate candidate) {
        selectedRect = new Rect(candidate.r);
        selectedId = candidate.id;
        selectedText = candidate.text;
        selectedSkip = false;
        showOverlay(selectedRect);
    }
    private void clearSelection() {
        selectedRect = null;
        selectedId = null;
        selectedText = null;
        selectedSkip = false;
        hideOverlay();
    }
    private void refreshSelection() {
        if (selectedRect == null) return;
        Rect old = new Rect(selectedRect);
        List<Candidate> candidates = scan();
        try {
            Candidate selected = findSelected(candidates);
            if (selected == null && inPlayer) {
                clearSelection();
                return;
            }
            if (selected == null) {
                double distance = Double.MAX_VALUE;
                for (Candidate candidate : candidates) {
                    double candidateDistance = Math.pow(candidate.r.centerX() - old.centerX(), 2)
                            + Math.pow(candidate.r.centerY() - old.centerY(), 2);
                    if (candidateDistance < distance) {
                        distance = candidateDistance;
                        selected = candidate;
                    }
                }
            }
            if (selected != null) setSelection(selected);
        } finally { release(candidates); }
    }
    private void release(List<Candidate> cs){for(Candidate c:cs)c.node.recycle();}
    private void clickSelected() {
        if (inPlayer) {
            clickPlayerSelected();
            return;
        }
        List<Candidate> candidates = scan();
        Candidate selected = findSelected(candidates);
        boolean clicked = false;
        AccessibilityNodeInfo target = selected == null ? null : AccessibilityNodeInfo.obtain(selected.node);
        try {
            while (target != null) {
                if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true;
                    break;
                }
                AccessibilityNodeInfo parent = target.getParent();
                target.recycle();
                target = parent;
            }
            Rect bounds = selectedRect == null ? null : new Rect(selectedRect);
            if (!clicked && bounds != null) tap(bounds.centerX(), bounds.centerY(), 50);
            else if (selected == null && candidates.isEmpty()) {
                tap(getResources().getDisplayMetrics().widthPixels / 2,
                        getResources().getDisplayMetrics().heightPixels / 2, 50);
            }
        } finally {
            if (target != null) target.recycle();
            release(candidates);
        }
        clearSelection();
        handler.postDelayed(new Runnable() {
            @Override public void run() { if (isNetflixActive()) selectFirst(); }
        }, 500);
    }
    private void handleEmpty(int key) {
        if (inPlayer) {
            PlayerState state = readPlayerState();
            try {
                if (controlsVisible) selectPlayPause(state);
                else if (state.skip == null) queuePlayerAction(PlayerAction.SELECT_PLAY_PAUSE, state);
            } finally { release(state.nodes); }
            return;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            tap(getResources().getDisplayMetrics().widthPixels / 2,
                    getResources().getDisplayMetrics().heightPixels / 2, 50);
            handler.postDelayed(new Runnable() {
                @Override public void run() { selectFirst(); }
            }, 300);
        }
    }
    private void scroll(int key){final Rect base=selectedRect==null?new Rect(0,0,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels):new Rect(selectedRect);baseForScroll.set(base);List<AccessibilityNodeInfo> scrollables=new ArrayList<AccessibilityNodeInfo>();AccessibilityNodeInfo root=null;try{root=getRootInActiveWindow();if(root!=null)collectScroll(root,scrollables,0);AccessibilityNodeInfo target=null;Rect tr=null;double dist=Double.MAX_VALUE;for(AccessibilityNodeInfo n:scrollables){Rect r=new Rect();n.getBoundsInScreen(r);boolean horiz=key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;if((horiz&&r.width()>=r.height())||(!horiz&&r.height()>=r.width())){double d=Math.pow(r.centerX()-base.centerX(),2)+Math.pow(r.centerY()-base.centerY(),2);if(d<dist){dist=d;target=n;tr=r;}}}int action=(key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_RIGHT)?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;boolean ok=target!=null&&target.performAction(action);Log.d(TAG,"scroll action="+action+" success="+ok);if(!ok){if(tr==null)tr=new Rect(0,0,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);int x=tr.centerX(),y=tr.centerY(),dx=0,dy=0;if(key==KeyEvent.KEYCODE_DPAD_DOWN)dy=-tr.height()*2/5;else if(key==KeyEvent.KEYCODE_DPAD_UP)dy=tr.height()*2/5;else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)dx=-tr.width()*2/5;else dx=tr.width()*2/5;swipe(x,y,x+dx,y+dy,250);}final int dir=key;handler.postDelayed(new Runnable(){public void run(){if(!enabled||!isNetflixActive()||isKeyboardVisible())return;refreshPlayerState();if(inPlayer)return;List<Candidate> cs=scan();Candidate best=null;double score=Double.MAX_VALUE;for(Candidate c:cs){double s=NavService.this.score(base,c.r,dir);if(s<score){score=s;best=c;}}if(best!=null)setSelection(best);release(cs);}},300);}catch(Exception ex){Log.w(TAG,"scroll failed",ex);}finally{if(root!=null)root.recycle();for(AccessibilityNodeInfo n:scrollables)n.recycle();}}
    private Rect baseForScroll=new Rect();
    private void collectScroll(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> o,int d){if(n==null||d>45)return;try{if(n.isScrollable())o.add(AccessibilityNodeInfo.obtain(n));for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=null;try{c=n.getChild(i);if(c!=null)collectScroll(c,o,d+1);}finally{if(c!=null)c.recycle();}}}catch(Exception ignored){}}
    private void tap(int x,int y,long ms){Path p=new Path();p.moveTo(x,y);GestureDescription.Builder b=new GestureDescription.Builder();b.addStroke(new GestureDescription.StrokeDescription(p,0,ms));dispatchGesture(b.build(),null,null);}
    private void swipe(int x,int y,int x2,int y2,long ms){Path p=new Path();p.moveTo(x,y);p.lineTo(x2,y2);GestureDescription.Builder b=new GestureDescription.Builder();b.addStroke(new GestureDescription.StrokeDescription(p,0,ms));dispatchGesture(b.build(),null,null);}
    private void showOverlay(Rect r){if(inPlayer&&selectedRect==null){hideOverlay();return;}if(!enabled||!isNetflixActive())return;if(isKeyboardVisible()){hideOverlay();return;}int[] pos={r.left,r.top};overlayParams.x=pos[0];overlayParams.y=pos[1];overlayParams.width=Math.max(1,r.width());overlayParams.height=Math.max(1,r.height());overlay.setStroke(6);try{if(!overlay.isAttached){wm.addView(overlay,overlayParams);overlay.isAttached=true;}else wm.updateViewLayout(overlay,overlayParams);}catch(Exception e){Log.w(TAG,"overlay update failed",e);}}
    private void hideOverlay(){if(overlay!=null&&overlay.isAttached){try{wm.removeView(overlay);}catch(Exception ignored){}overlay.isAttached=false;}}
    private static class Highlight extends View {private final Paint p=new Paint(3);boolean isAttached=false;Highlight(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}void setStroke(float s){p.setStrokeWidth(s);invalidate();}@Override protected void onDraw(Canvas c){super.onDraw(c);float s=p.getStrokeWidth();p.setStyle(Paint.Style.STROKE);p.setColor(0xaa000000);p.setStrokeWidth(s+3);c.drawRoundRect(s/2,s/2,getWidth()-s/2,getHeight()-s/2,14,14,p);p.setColor(Color.rgb(255,214,0));p.setStrokeWidth(s);c.drawRoundRect(s/2,s/2,getWidth()-s/2,getHeight()-s/2,14,14,p);}}
}
