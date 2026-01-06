package communicationmod;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.neow.NeowRoom;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase;
import com.megacrit.cardcrawl.rooms.EventRoom;
import com.megacrit.cardcrawl.rooms.VictoryRoom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GameStateListener {
    private static final Logger logger = LogManager.getLogger(GameStateListener.class.getName());
    private static AbstractDungeon.CurrentScreen previousScreen = null;
    private static boolean previousScreenUp = false;
    private static AbstractRoom.RoomPhase previousPhase = null;
    private static boolean previousGridSelectConfirmUp = false;
    private static int previousGold = 99;
    private static boolean externalChange = false;
    private static boolean myTurn = false;
    private static boolean blocked = false;
    private static boolean waitingForCommand = false;
    private static boolean hasPresentedOutOfGameState = false;
    private static boolean waitOneUpdate = false;
    private static int timeout = 0;

    // Wait condition tracking
    public enum WaitCondition {
        NONE,           // Not waiting for anything
        IN_GAME,        // Waiting for in_game == targetValue
        IN_COMBAT,      // Waiting for in_combat == targetValue
        MAIN_MENU,      // Waiting for main menu (in_game == false)
        VISUAL_STABLE   // Waiting for visual effects to complete
    }
    private static WaitCondition waitCondition = WaitCondition.NONE;
    private static boolean waitConditionTargetValue = false;

    // Timeout tracking for visual stability wait
    private static long visualStableWaitStartTime = 0;
    private static final long VISUAL_STABLE_TIMEOUT_MS = 30000; // 30 seconds

    // Error message to include in next response (cleared after sending)
    private static String lastError = null;

    // Force ready_for_command=true on next state send (for commands like arena_back)
    private static boolean forceReadyOnNextSend = false;

    /**
     * Used to indicate that something (in game logic, not external command) has been done that will change the game state,
     * and hasStateChanged() should indicate a state change when the state next becomes stable.
     */
    public static void registerStateChange() {
        externalChange = true;
        waitingForCommand = false;
    }

    /**
     * Used to tell hasStateChanged() to indicate a state change after a specified number of frames.
     * @param newTimeout The number of frames to wait
     */
    public static void setTimeout(int newTimeout) {
        timeout = newTimeout;
    }

    /**
     * Used to indicate that an external command has been executed
     */
    public static void registerCommandExecution() {
        waitingForCommand = false;
    }

    /**
     * Prevents hasStateChanged() from indicating a state change until resumeStateUpdate() is called.
     */
    public static void blockStateUpdate() {
        blocked = true;
    }

    /**
     * Removes the block instantiated by blockStateChanged()
     */
    public static void resumeStateUpdate() {
        blocked = false;
    }

    /**
     * Used by a patch in the game to signal the start of your turn. We do not care about state changes
     * when it is not our turn in combat, as we cannot take action until then.
     */
    public static void signalTurnStart() {
        myTurn = true;
    }

    /**
     * Used by patches in the game to signal the end of your turn (or the end of combat).
     */
    public static void signalTurnEnd() {
        myTurn = false;
    }

    /**
     * Signals that the game should report ready_for_command=true on the next state send.
     * Used by command extensions that need to force a state response
     * even when no detectable state change has occurred.
     *
     * This is different from directly setting waitingForCommand because that flag
     * gets reset by registerCommandExecution() after command execution completes.
     * This flag persists until the state is actually sent.
     */
    public static void signalReadyForCommand() {
        forceReadyOnNextSend = true;
    }

    /**
     * Resets all state detection variables for the start of a new run.
     */
    public static void resetStateVariables() {
        previousScreen = null;
        previousScreenUp = false;
        previousPhase = null;
        previousGridSelectConfirmUp = false;
        previousGold = 99;
        externalChange = false;
        myTurn = false;
        blocked = false;
        waitingForCommand = false;
        waitOneUpdate = false;
        waitCondition = WaitCondition.NONE;
        waitConditionTargetValue = false;
        forceReadyOnNextSend = false;
    }

    /**
     * Sets a wait condition. The game will send a state update when the condition is met.
     * @param condition The condition to wait for
     * @param targetValue The target value for the condition
     */
    public static void setWaitCondition(WaitCondition condition, boolean targetValue) {
        waitCondition = condition;
        waitConditionTargetValue = targetValue;
        waitingForCommand = false; // Not ready until condition is met

        // Start timeout timer for VISUAL_STABLE
        if (condition == WaitCondition.VISUAL_STABLE) {
            startVisualStableWait();
        }
    }

    /**
     * Clears any active wait condition.
     */
    public static void clearWaitCondition() {
        waitCondition = WaitCondition.NONE;
        waitConditionTargetValue = false;
    }

    /**
     * Checks if a wait condition is active and whether it's now satisfied.
     * @return true if there was a wait condition and it's now met
     */
    public static boolean checkWaitConditionMet() {
        if (waitCondition == WaitCondition.NONE) {
            return false;
        }

        boolean conditionMet = false;

        switch (waitCondition) {
            case IN_GAME:
                boolean currentlyInGame = CommandExecutor.isInDungeon();
                conditionMet = (currentlyInGame == waitConditionTargetValue);
                break;
            case IN_COMBAT:
                if (CommandExecutor.isInDungeon()) {
                    boolean inCombat = AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT;
                    conditionMet = (inCombat == waitConditionTargetValue);
                } else {
                    // If not in dungeon and waiting for in_combat=false, that's satisfied
                    conditionMet = !waitConditionTargetValue;
                }
                break;
            case MAIN_MENU:
                // Waiting for main menu = not in dungeon AND either:
                // 1. In CHAR_SELECT mode with mainMenuScreen visible, OR
                // 2. Game mode is null/SPLASH (transitioning but definitely not in dungeon)
                // This allows detecting return to menu during transitions
                boolean notInDungeon = !CommandExecutor.isInDungeon();
                boolean atCharSelect = CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT
                    && CardCrawlGame.mainMenuScreen != null;
                boolean atSplash = CardCrawlGame.mode == CardCrawlGame.GameMode.SPLASH;
                conditionMet = notInDungeon && (atCharSelect || atSplash);
                break;
            case VISUAL_STABLE:
                // Wait for visual effects to complete (no fading, no effects playing)
                // This is useful for screenshot capture and visual verification
                if (hasVisualStableWaitTimedOut()) {
                    // Timeout - report error and proceed
                    setError("Timeout waiting for visual stability after 30 seconds");
                    clearVisualStableWait();
                    conditionMet = true;
                } else {
                    conditionMet = areVisualEffectsStable();
                    if (conditionMet) {
                        clearVisualStableWait();
                    }
                }
                break;
        }

        if (conditionMet) {
            waitCondition = WaitCondition.NONE;
            waitConditionTargetValue = false;
            waitingForCommand = true;
            return true;
        }
        return false;
    }

    /**
     * @return true if we're currently waiting for a condition
     */
    public static boolean isWaitingForCondition() {
        return waitCondition != WaitCondition.NONE;
    }

    /**
     * Checks if visual effects have completed and the screen is stable.
     * This checks for common transition indicators:
     * - Game mode is in a stable state (GAMEPLAY or at main menu)
     * - Not loading a save file
     * - No fading in/out (AbstractDungeon flags and fadeTimer)
     * - No screen swap in progress
     * - Room wait timer is zero
     * - CardCrawlGame screen timer is zero
     * - Main menu not fading
     *
     * Note: We don't check if effect lists are empty because there are always
     * ambient effects (particles, lighting) playing during normal gameplay.
     *
     * @return true if visual effects are stable
     */
    public static boolean areVisualEffectsStable() {
        // Check if the game is in a true transitional mode (SPLASH only)
        // Note: CHAR_SELECT is the normal main menu state, so we don't block on it
        CardCrawlGame.GameMode mode = CardCrawlGame.mode;
        if (mode == CardCrawlGame.GameMode.SPLASH) {
            logger.info("Visual stability blocked: CardCrawlGame.mode=" + mode);
            return false;
        }

        // Check if we're loading a save (transition in progress)
        // This catches the CHAR_SELECT -> GAMEPLAY transition
        if (CardCrawlGame.loadingSave) {
            logger.info("Visual stability blocked: CardCrawlGame.loadingSave=true");
            return false;
        }

        // Note: We intentionally don't check mainMenuScreen.isFadingOut because it can
        // stay true for extended periods during transitions. The loadingSave and mode
        // checks above are sufficient to catch actual transitions.

        // Check if we're in a dungeon - different checks apply
        if (CommandExecutor.isInDungeon()) {
            // Check for dungeon fading
            if (AbstractDungeon.isFadingIn || AbstractDungeon.isFadingOut) {
                logger.info("Visual stability blocked: isFadingIn=" + AbstractDungeon.isFadingIn + " isFadingOut=" + AbstractDungeon.isFadingOut);
                return false;
            }

            // Check fade timer via reflection (it's protected)
            try {
                java.lang.reflect.Field fadeTimerField = AbstractDungeon.class.getDeclaredField("fadeTimer");
                fadeTimerField.setAccessible(true);
                float fadeTimer = fadeTimerField.getFloat(null);
                if (fadeTimer > 0) {
                    logger.info("Visual stability blocked: fadeTimer=" + fadeTimer);
                    return false;
                }
            } catch (Exception e) {
                // If reflection fails, fall through to other checks
                logger.info("Visual stability: fadeTimer reflection failed: " + e.getMessage());
            }

            // Check for screen swap in progress
            if (AbstractDungeon.screenSwap) {
                logger.info("Visual stability blocked: screenSwap=true");
                return false;
            }

            // Note: We intentionally don't check waitingOnFadeOut because it can stay
            // true indefinitely in some states. The isFadingIn/Out flags are sufficient.

            // Check room wait timer - allow very small values (< 100ms) to avoid blocking
            // on timers that should naturally tick down in a few frames. This fixes issues
            // where the timer hovers at a small value in certain game states (e.g., after
            // starting a new run while sitting in Neow's room).
            if (AbstractRoom.waitTimer > 0.1f) {
                logger.info("Visual stability blocked: AbstractRoom.waitTimer=" + AbstractRoom.waitTimer);
                return false;
            }

            // Check if current room exists and has an event with wait timer
            // Same as above: allow small values (< 100ms) to pass through
            AbstractRoom currentRoom = AbstractDungeon.getCurrRoom();
            if (currentRoom != null) {
                if ((currentRoom instanceof EventRoom || currentRoom instanceof NeowRoom)
                        && currentRoom.event != null
                        && currentRoom.event.waitTimer > 0.1f) {
                    logger.info("Visual stability blocked: event.waitTimer=" + currentRoom.event.waitTimer);
                    return false;
                }
            }
        }

        // Check CardCrawlGame screen timer (applies both in and out of dungeon)
        if (CardCrawlGame.screenTimer > 0) {
            logger.info("Visual stability blocked: CardCrawlGame.screenTimer=" + CardCrawlGame.screenTimer);
            return false;
        }

        logger.info("Visual stability: all checks passed, stable!");
        return true;
    }

    /**
     * Starts the visual stability wait timer.
     */
    public static void startVisualStableWait() {
        visualStableWaitStartTime = System.currentTimeMillis();
    }

    /**
     * Checks if the visual stability wait has timed out.
     * @return true if more than 30 seconds have elapsed since startVisualStableWait()
     */
    public static boolean hasVisualStableWaitTimedOut() {
        if (visualStableWaitStartTime == 0) {
            return false;
        }
        return (System.currentTimeMillis() - visualStableWaitStartTime) > VISUAL_STABLE_TIMEOUT_MS;
    }

    /**
     * Clears the visual stability wait timer.
     */
    public static void clearVisualStableWait() {
        visualStableWaitStartTime = 0;
    }

    /**
     * Sets an error message to be included in the next response.
     */
    public static void setError(String error) {
        lastError = error;
    }

    /**
     * Gets and clears the last error message.
     * @return the error message, or null if none
     */
    public static String getAndClearError() {
        String error = lastError;
        lastError = null;
        return error;
    }

    /**
     * @return true if there is a pending error
     */
    public static boolean hasError() {
        return lastError != null;
    }

    /**
     * Detects whether the game state is stable and we are ready to receive a command from the user.
     *
     * @return whether the state is stable
     */
    private static boolean hasDungeonStateChanged() {
        if (blocked) {
            return false;
        }
        hasPresentedOutOfGameState = false;
        AbstractDungeon.CurrentScreen newScreen = AbstractDungeon.screen;
        boolean newScreenUp = AbstractDungeon.isScreenUp;
        AbstractRoom.RoomPhase newPhase = AbstractDungeon.getCurrRoom().phase;
        boolean inCombat = (newPhase == AbstractRoom.RoomPhase.COMBAT);
        // Lots of stuff can happen while the dungeon is fading out, but nothing that requires input from the user.
        if (AbstractDungeon.isFadingOut || AbstractDungeon.isFadingIn) {
            return false;
        }
        // This check happens before the rest since dying can happen in combat and messes with the other cases.
        if (newScreen == AbstractDungeon.CurrentScreen.DEATH && newScreen != previousScreen) {
            return true;
        }
        // These screens have no interaction available.
        if (newScreen == AbstractDungeon.CurrentScreen.DOOR_UNLOCK || newScreen == AbstractDungeon.CurrentScreen.NO_INTERACT) {
            return false;
        }
        // We are not ready to receive commands when it is not our turn, except for some pesky screens
        if (inCombat && (!myTurn || AbstractDungeon.getMonsters().areMonstersBasicallyDead())) {
            if (!newScreenUp) {
                return false;
            }
        }
        // In event rooms, we need to wait for the event wait timer to reach 0 before we can accurately assess its state.
        AbstractRoom currentRoom = AbstractDungeon.getCurrRoom();
        if ((currentRoom instanceof EventRoom
                || currentRoom instanceof NeowRoom
                || (currentRoom instanceof VictoryRoom && ((VictoryRoom) currentRoom).eType == VictoryRoom.EventType.HEART))
                && AbstractDungeon.getCurrRoom().event.waitTimer != 0.0F) {
            return false;
        }
        // The state has always changed in some way when one of these variables is different.
        // However, the state may not be finished changing, so we need to do some additional checks.
        if (newScreen != previousScreen || newScreenUp != previousScreenUp || newPhase != previousPhase) {
            if (inCombat) {
                // In combat, newScreenUp being true indicates an action that requires our immediate attention.
                if (newScreenUp) {
                    return true;
                }
                // In combat, if no screen is up, we should wait for all actions to complete before indicating a state change.
                else if (AbstractDungeon.actionManager.phase.equals(GameActionManager.Phase.WAITING_ON_USER)
                        && AbstractDungeon.actionManager.cardQueue.isEmpty()
                        && AbstractDungeon.actionManager.actions.isEmpty()) {
                    return true;
                }

            // Out of combat, we want to wait one update cycle, as some screen transitions trigger further updates.
            } else {
                waitOneUpdate = true;
                previousScreenUp = newScreenUp;
                previousScreen = newScreen;
                previousPhase = newPhase;
                return false;
            }
        } else if (waitOneUpdate) {
            waitOneUpdate = false;
            return true;
        }
        // We are assuming that commands are only being submitted through our interface. Some actions that require
        // our attention, like retaining a card, occur after the end turn is queued, but the previous cases
        // cover those actions. We would like to avoid registering other state changes after the end turn
        // command but before the game actually ends your turn.
        if (inCombat && AbstractDungeon.player.endTurnQueued) {
            return false;
        }
        // If some other code registered a state change through registerStateChange(), or if we notice a state
        // change through the gold amount changing, we still need to wait until all actions are finished
        // resolving to claim a stable state and ask for a new command.
        if ((externalChange || previousGold != AbstractDungeon.player.gold)
                && AbstractDungeon.actionManager.phase.equals(GameActionManager.Phase.WAITING_ON_USER)
                && AbstractDungeon.actionManager.preTurnActions.isEmpty()
                && AbstractDungeon.actionManager.actions.isEmpty()
                && AbstractDungeon.actionManager.cardQueue.isEmpty()) {
            return true;
        }
        // In a grid select screen, if a confirm screen comes up or goes away, it doesn't change any other state.
        if (newScreen == AbstractDungeon.CurrentScreen.GRID) {
            boolean newGridSelectConfirmUp = AbstractDungeon.gridSelectScreen.confirmScreenUp;
            if (previousScreen == AbstractDungeon.CurrentScreen.GRID && newGridSelectConfirmUp != previousGridSelectConfirmUp) {
                return true;
            }
        }
        // Sometimes, we need to register an external change in combat while an action is resolving which brings
        // the screen up. Because the screen did not change, this is not covered by other cases.
        if (externalChange && inCombat && newScreenUp) {
            return true;
        }
        if (timeout > 0) {
            timeout -= 1;
            if(timeout == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects whether the state of the game menu has changed. Right now, this only occurs when you first enter the
     * menu, either after starting Slay the Spire for the first time, or after ending a game and returning to the menu.
     *
     * @return Whether the main menu has just been entered.
     */
    public static boolean checkForMenuStateChange() {
        boolean stateChange = false;
        if (!hasPresentedOutOfGameState && CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT && CardCrawlGame.mainMenuScreen != null) {
            stateChange = true;
            hasPresentedOutOfGameState = true;
        }
        if (stateChange) {
            externalChange = false;
            waitingForCommand = true;
        }
        return stateChange;
    }

    /**
     * Detects a state change in AbstractDungeon, and updates all of the local variables used to detect
     * changes in the dungeon state. Sets waitingForCommand = true if a state change was registered since
     * the last command was sent.
     *
     * @return Whether a dungeon state change was detected
     */
    public static boolean checkForDungeonStateChange() {
        boolean stateChange = false;
        if (CommandExecutor.isInDungeon()) {
            stateChange = hasDungeonStateChanged();
            if (stateChange) {
                externalChange = false;
                waitingForCommand = true;
                previousPhase = AbstractDungeon.getCurrRoom().phase;
                previousScreen = AbstractDungeon.screen;
                previousScreenUp = AbstractDungeon.isScreenUp;
                previousGold = AbstractDungeon.player.gold;
                previousGridSelectConfirmUp = AbstractDungeon.gridSelectScreen.confirmScreenUp;
                timeout = 0;
            }
        } else {
            myTurn = false;
        }
        return stateChange;
    }

    public static boolean isWaitingForCommand() {
        // Check if a command requested forced ready state (e.g., arena_back)
        if (forceReadyOnNextSend) {
            forceReadyOnNextSend = false;  // Consume the flag
            return true;
        }
        return waitingForCommand;
    }
}