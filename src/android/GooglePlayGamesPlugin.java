package io.luzh.cordova.plugin;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.images.ImageManager;

import com.google.android.gms.games.AnnotatedData;
import com.google.android.gms.games.PlayGamesSdk;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.GamesSignInClient;

import com.google.android.gms.games.leaderboard.LeaderboardScore;
import com.google.android.gms.games.leaderboard.LeaderboardVariant;

import com.google.android.gms.games.SnapshotsClient;
import com.google.android.gms.games.snapshot.Snapshot;
import com.google.android.gms.games.snapshot.SnapshotMetadata;

import com.google.android.gms.games.FriendsResolutionRequiredException;
import com.google.android.gms.games.PlayerBuffer;

import com.google.android.gms.games.stats.PlayerStats;

import android.content.Intent;

import com.google.android.gms.games.snapshot.SnapshotMetadataChange;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

public class GooglePlayGamesPlugin extends CordovaPlugin {

    private static final String TAG = "GOOGLE_PLAY_GAMES";

    private static final int RC_ACHIEVEMENT_UI = 9003;
    private static final int RC_LEADERBOARD_UI = 9004;
    private static final int RC_SAVED_GAMES = 9009;
    private static final int RC_SHOW_PROFILE = 9010;
    private static final int SHOW_SHARING_FRIENDS_CONSENT = 1111;

    private static final String EVENT_LOAD_SAVED_GAME_REQUEST = "loadSavedGameRequest";
    private static final String EVENT_SAVE_GAME_REQUEST = "saveGameRequest";
    private static final String EVENT_SAVE_GAME_CONFLICT = "saveGameConflict";
    private static final String EVENT_FRIENDS_LIST_REQUEST_SUCCESSFUL = "friendsListRequestSuccessful";

    private static final int ERROR_CODE_HAS_RESOLUTION = 1;
    private static final int ERROR_CODE_NO_RESOLUTION = 2;

    private RelativeLayout bannerContainerLayout;
    private CordovaWebView cordovaWebView;
    private ViewGroup parentLayout;

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {

        if (action.equals("init")) {
            this.initAction(args, callbackContext);
            return true;
        }

        else if (action.equals("login")) {
            this.loginAction(args, callbackContext);
            return true;
        }

        else if (action.equals("unlockAchievement")) {
            this.unlockAchievementAction(args.getString(0), callbackContext);
            return true;
        }

        else if (action.equals("incrementAchievement")) {
            this.incrementAchievementAction(args.getString(0), args.getInt(1), callbackContext);
            return true;
        }

        else if (action.equals("showAchievements")) {
            this.showAchievementsAction(callbackContext);
            return true;
        }

        else if (action.equals("updatePlayerScore")) {
            this.updatePlayerScoreAction(args.getString(0), args.getInt(1), callbackContext);
            return true;
        }

        else if (action.equals("loadPlayerScore")) {
            this.loadPlayerScoreAction(args.getString(0), callbackContext);
            return true;
        }

        else if (action.equals("showLeaderboard")) {
            this.showLeaderboardAction(args.getString(0), callbackContext);
            return true;
        }

        else if (action.equals("showSavedGames")) {
            this.showSavedGamesAction(args.getString(0), args.getBoolean(1), args.getBoolean(2), args.getInt(3), callbackContext);
            return true;
        }

        else if (action.equals("saveGame")) {
            this.saveGameAction(args.getString(0), args.getString(1), args.getJSONObject(2), callbackContext);
            return true;
        }

        else if (action.equals("loadGameSave")) {
            this.loadGameSaveAction(args.getString(0), callbackContext);
            return true;
        }

        else if (action.equals("getFriendsList")) {
            this.getFriendsListAction(callbackContext);
            return true;
        }

        else if (action.equals("showAnotherPlayersProfile")) {
            this.showAnotherPlayersProfileAction(args.getString(0), callbackContext);
            return true;
        }

        else if (action.equals("getCurrentPlayerStats")) {
            this.getCurrentPlayerStatsAction(callbackContext);
            return true;
        }

        return false;
    }

    /** --------------------------------------------------------------- */

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        cordovaWebView = webView;
        super.initialize(cordova, webView);
        PlayGamesSdk.initialize(cordova.getActivity());
    }

    /** ----------------------- UTILS --------------------------- */

    private void emitWindowEvent(final String event) {
        final CordovaWebView view = this.webView;
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.loadUrl(String.format("javascript:cordova.fireWindowEvent('%s');", event));
            }
        });
    }

    private void emitWindowEvent(final String event, final JSONObject data) {
        final CordovaWebView view = this.webView;
        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.loadUrl(String.format("javascript:cordova.fireWindowEvent('%s', %s);", event, data.toString()));
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        GooglePlayGamesPlugin self = this;
        Log.d(TAG, "onActivityResult");
        if (intent != null) {
            if (intent.hasExtra(SnapshotsClient.EXTRA_SNAPSHOT_METADATA)) {
                // Load a snapshot.
                SnapshotMetadata snapshotMetadata =
                        intent.getParcelableExtra(SnapshotsClient.EXTRA_SNAPSHOT_METADATA);
                String mCurrentSaveName = snapshotMetadata.getUniqueName();
                try {
                    JSONObject result = new JSONObject();
                    result.put("id", mCurrentSaveName);
                    self.emitWindowEvent(EVENT_LOAD_SAVED_GAME_REQUEST, result);
                } catch (JSONException err) {
                    Log.d(TAG, "onActivityResult error", err);
                }
            } else if (intent.hasExtra(SnapshotsClient.EXTRA_SNAPSHOT_NEW)) {
                // Create a new snapshot named with a unique string
                self.emitWindowEvent(EVENT_SAVE_GAME_REQUEST);
            } else if (requestCode == SHOW_SHARING_FRIENDS_CONSENT) {
                if (resultCode == Activity.RESULT_OK) {
                    Log.e(TAG, "Load friends: OK");
                    self.emitWindowEvent(EVENT_FRIENDS_LIST_REQUEST_SUCCESSFUL);
                } else {
                    Log.e(TAG, "Load friends: No access");
                }
            }
        }
    }

    /** ----------------------- INITIALIZATION --------------------------- */

    /**
     * Intilization action Initializes Yandex Ads
     */
    private void initAction(JSONArray args, final CallbackContext callbackContext) throws JSONException {

    }

    /**
     * Initializes Rewarded ad
     */
    private void loginAction(JSONArray args, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                GamesSignInClient gamesSignInClient = PlayGames.getGamesSignInClient(cordova.getActivity());

                gamesSignInClient.isAuthenticated().addOnCompleteListener(isAuthenticatedTask -> {
                    boolean isAuthenticated =
                            (isAuthenticatedTask.isSuccessful() &&
                                    isAuthenticatedTask.getResult().isAuthenticated());

                    if (isAuthenticated) {
                        PlayGames.getPlayersClient(cordova.getActivity()).getCurrentPlayer().addOnCompleteListener(mTask -> {
                                try {
                                    JSONObject result = new JSONObject();
                                    result.put("id", mTask.getResult().getPlayerId());
                                    callbackContext.success(result);
                                } catch (JSONException err) {
                                    callbackContext.error(err.getMessage());
                                }
                            }
                        );
                    } else {
                        callbackContext.error("Login failed");
                    }
                });
            }
        });
    }

    /**
     * Unlock achievement
     */
    private void unlockAchievementAction(String achievementId, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getAchievementsClient(cordova.getActivity()).unlock(achievementId);
                callbackContext.success();
            }
        });
    }

    /**
     * Increment achievement
     */
    private void incrementAchievementAction(String achievementId, Integer count, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getAchievementsClient(cordova.getActivity()).increment(achievementId, count);
                callbackContext.success();
            }
        });
    }

    /**
     * Show achievements
     */
    private void showAchievementsAction(final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getAchievementsClient(cordova.getActivity())
                        .getAchievementsIntent()
                        .addOnSuccessListener(new OnSuccessListener<Intent>() {
                            @Override
                            public void onSuccess(Intent intent) {
                                cordova.getActivity().startActivityForResult(intent, RC_ACHIEVEMENT_UI);
                            }
                        });
                callbackContext.success();
            }
        });
    }

    /**
     * Update player score
     */
    private void updatePlayerScoreAction(String leaderboardId, Integer score, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getLeaderboardsClient(cordova.getActivity())
                        .submitScore(leaderboardId, score);
                callbackContext.success();
            }
        });
    }

    /**
     * Load player score
     */
    private void loadPlayerScoreAction(String leaderboardId, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getLeaderboardsClient(cordova.getActivity())
                    .loadCurrentPlayerLeaderboardScore(leaderboardId, LeaderboardVariant.TIME_SPAN_ALL_TIME, LeaderboardVariant.COLLECTION_PUBLIC)
                    .addOnSuccessListener(new OnSuccessListener<AnnotatedData<LeaderboardScore>>() {
                        @Override
                        public void onSuccess(AnnotatedData<LeaderboardScore> score) {
                            try {
                                @Nullable LeaderboardScore scoreObject = score.get();
                                JSONObject result = new JSONObject();
                                result.put("score", scoreObject != null ? scoreObject.getRawScore() : 0);
                                callbackContext.success(result);
                            } catch (JSONException err) {
                                callbackContext.error(err.getMessage());
                            }
                        }
                    });
            }
        });
    }

    /**
     * Show leaderboard
     */
    private void showLeaderboardAction(String leaderboardId, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getLeaderboardsClient(cordova.getActivity())
                        .getLeaderboardIntent(leaderboardId)
                        .addOnSuccessListener(new OnSuccessListener<Intent>() {
                            @Override
                            public void onSuccess(Intent intent) {
                                cordova.getActivity().startActivityForResult(intent, RC_LEADERBOARD_UI);
                            }
                        });
            }
        });
    }

    /**
     * Show saved games
     */
    private void showSavedGamesAction(String title, Boolean allowAddButton, Boolean allowDelete, Integer numberOfSavedGames, final CallbackContext callbackContext) {
        GooglePlayGamesPlugin self = this;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                SnapshotsClient snapshotsClient =
                        PlayGames.getSnapshotsClient(cordova.getActivity());
                int maxNumberOfSavedGamesToShow = numberOfSavedGames;

                snapshotsClient
                    .getSelectSnapshotIntent(title, allowAddButton, allowDelete, maxNumberOfSavedGamesToShow)
                    .addOnSuccessListener(
                        new OnSuccessListener<Intent>(){
                            @Override
                            public void onSuccess(Intent intent) {
                                cordova.setActivityResultCallback(self);
                                cordova.getActivity().startActivityForResult(intent, RC_SAVED_GAMES);
                                callbackContext.success();
                            }
                        }
                    );
            }
        });
    }

    /**
     * Save game
     */
    private void saveGameAction(String snapshotName, String snapshotDescription, JSONObject snapshotContents, final CallbackContext callbackContext) {
        GooglePlayGamesPlugin self = this;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                int conflictResolutionPolicy = SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED;
                SnapshotsClient snapshotsClient =
                        PlayGames.getSnapshotsClient(cordova.getActivity());
                snapshotsClient
                    .open(snapshotName, true, conflictResolutionPolicy)
                    .addOnSuccessListener(new OnSuccessListener<SnapshotsClient.DataOrConflict<Snapshot>>(){
                        @Override
                        public void onSuccess(SnapshotsClient.DataOrConflict<Snapshot> dataOrConflict) {
                            if (dataOrConflict.isConflict()) {
                                // Send conflict id
                                try {
                                    JSONObject result = new JSONObject();
                                    result.put("conflictId", dataOrConflict.getConflict().getConflictId());
                                    self.emitWindowEvent(EVENT_SAVE_GAME_CONFLICT, result);
                                } catch (JSONException err) {
                                    callbackContext.error(err.getMessage());
                                }
                                return;
                            }
                            // Set the data payload for the snapshot
                            dataOrConflict.getData().getSnapshotContents().writeBytes(snapshotContents.toString().getBytes(StandardCharsets.UTF_8));

                            // Create the change operation
                            SnapshotMetadataChange metadataChange = new SnapshotMetadataChange.Builder()
                                    .setDescription(snapshotDescription)
                                    .build();

                            // Commit the operation
                            snapshotsClient.commitAndClose(dataOrConflict.getData(), metadataChange);
                            callbackContext.success();
                        }
                    });
            }
        });
    }

    /**
     * Save game
     */
    private void loadGameSaveAction(String snapshotName, final CallbackContext callbackContext) {
        GooglePlayGamesPlugin self = this;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                Log.e(TAG, "READ START");
                SnapshotsClient snapshotsClient =
                        PlayGames.getSnapshotsClient(cordova.getActivity());
                // In the case of a conflict, the most recently modified version of this snapshot will be used.
                int conflictResolutionPolicy = SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED;

                // Open the saved game using its name.
                snapshotsClient
                    .open(snapshotName, true, conflictResolutionPolicy)
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.e(TAG, "Error while opening Snapshot.", e);
                            callbackContext.error(e.getMessage());
                        }
                    })
                    .continueWith(new Continuation<SnapshotsClient.DataOrConflict<Snapshot>, byte[]>() {
                        @Override
                        public byte[] then(@NonNull Task<SnapshotsClient.DataOrConflict<Snapshot>> task) throws Exception {
                            Snapshot snapshot = task.getResult().getData();
                            // Opening the snapshot was a success and any conflicts have been resolved.
                            try {
                                // Extract the raw data from the snapshot.
                                return snapshot.getSnapshotContents().readFully();
                            } catch (IOException e) {
                                Log.e(TAG, "Error while reading Snapshot.", e);
                                callbackContext.error(e.getMessage());
                            }
                            return null;
                        }
                    })
                    .addOnCompleteListener(new OnCompleteListener<byte[]>() {
                        @Override
                        public void onComplete(@NonNull Task<byte[]> task) {
                            task.addOnSuccessListener(new OnSuccessListener<byte[]>() {
                                @Override
                                public void onSuccess(byte[] bytes) {
                                    try {
                                        Log.e(TAG, "READ SUCCESS");
                                        callbackContext.success(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
                                    } catch (JSONException e) {
                                        callbackContext.error(e.getMessage());
                                    }
                                }
                            });
                        }
                    });
            }
        });
    }

    private void getFriendsListAction(final CallbackContext callbackContext) {
        GooglePlayGamesPlugin self = this;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                int pageSize = 200;
                PlayGames.getPlayersClient(cordova.getActivity())
                        .loadFriends(pageSize,false)
                        .addOnSuccessListener(
                            new OnSuccessListener<AnnotatedData<PlayerBuffer>>() {
                                @Override
                                public void onSuccess(AnnotatedData<PlayerBuffer> data) {
                                    PlayerBuffer playerBuffer = data.get();

                                    JSONArray players = new JSONArray();
                                    try {
                                        AtomicInteger currentCount = new AtomicInteger();
                                        int totalCount = playerBuffer.getCount();
                                        for (int i = 0; i < playerBuffer.getCount(); i++) {
                                            JSONObject player = new JSONObject();
                                            player.put("id", playerBuffer.get(i).getPlayerId());
                                            player.put("name", playerBuffer.get(i).getDisplayName());
                                            player.put("title", playerBuffer.get(i).getTitle());
                                            player.put("retrievedTimestamp", playerBuffer.get(i).getRetrievedTimestamp());
                                            if (playerBuffer.get(i).getBannerImageLandscapeUri() != null) {
                                                player.put("bannerImageLandscapeUri", playerBuffer.get(i).getBannerImageLandscapeUri().toString());
                                            }
                                            if (playerBuffer.get(i).getBannerImagePortraitUri() != null) {
                                                player.put("bannerImagePortraitUri", playerBuffer.get(i).getBannerImagePortraitUri().toString());
                                            }
                                            if (playerBuffer.get(i).hasIconImage()) {
                                                player.put("iconImageUri", playerBuffer.get(i).getIconImageUri().toString());
                                            }
                                            if (playerBuffer.get(i).hasHiResImage()) {
                                                player.put("hiResImageUri", playerBuffer.get(i).getHiResImageUri().toString());
                                            }
                                            if (playerBuffer.get(i).getLevelInfo() != null) {
                                                JSONObject levelInfo = new JSONObject();
                                                levelInfo.put("currentLevel", playerBuffer.get(i).getLevelInfo().getCurrentLevel().getLevelNumber());
                                                levelInfo.put("maxXp", playerBuffer.get(i).getLevelInfo().getCurrentLevel().getMaxXp());
                                                levelInfo.put("minXp", playerBuffer.get(i).getLevelInfo().getCurrentLevel().getMinXp());
                                                levelInfo.put("hashCode", playerBuffer.get(i).getLevelInfo().getCurrentLevel().hashCode());
                                                player.put("levelInfo", levelInfo);
                                            }
                                            if (playerBuffer.get(i).getCurrentPlayerInfo() != null) {
                                                JSONObject currentPlayerInfo = new JSONObject();
                                                currentPlayerInfo.put("friendsListVisibilityStatus", playerBuffer.get(i).getCurrentPlayerInfo().getFriendsListVisibilityStatus());
                                                player.put("currentPlayerInfo", currentPlayerInfo);
                                            }
                                            if (playerBuffer.get(i).getRelationshipInfo() != null) {
                                                player.put("friendStatus", playerBuffer.get(i).getRelationshipInfo().getFriendStatus());
                                            }

                                            if (playerBuffer.get(i).hasIconImage()) {
                                                ImageManager mgr = ImageManager.create(cordova.getContext());
                                                mgr.loadImage((uri, drawable, isRequestedDrawable) -> {
                                                    if (isRequestedDrawable) {
                                                        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                                                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                                                        byte[] byteArray = byteArrayOutputStream.toByteArray();
                                                        String encoded = Base64.encodeToString(byteArray, Base64.DEFAULT);
                                                        try {
                                                            player.put("iconImageBase64", "data:image/png;base64, " + encoded);
                                                            players.put(player);
                                                            currentCount.addAndGet(1);
                                                            if (currentCount.intValue() >= totalCount) {
                                                                callbackContext.success(players);
                                                            }
                                                        } catch (Exception e) {
                                                            Log.e(TAG, "Error while getting base64 for user from friend list.");
                                                        }
                                                    }
                                                }, Objects.requireNonNull(playerBuffer.get(i).getIconImageUri()));
                                            }
                                        }
                                    } catch (JSONException err) {
                                        try {
                                            JSONObject error = new JSONObject();
                                            error.put("code", ERROR_CODE_NO_RESOLUTION);
                                            error.put("message", "Error while retrieving friends list: " + err.getMessage());
                                            callbackContext.error(error);
                                        } catch (JSONException e) {
                                            callbackContext.error("Error while retrieving friends list: " + e.getMessage());
                                        }
                                    }
                                }
                            }
                        )
                        .addOnFailureListener(exception -> {
                            if (exception instanceof FriendsResolutionRequiredException) {
                                PendingIntent pendingIntent =
                                        ((FriendsResolutionRequiredException) exception).getResolution();
                                try {
                                    cordova.setActivityResultCallback(self);
                                    cordova.getActivity().startIntentSenderForResult(
                                            pendingIntent.getIntentSender(),
                                            /* requestCode */ SHOW_SHARING_FRIENDS_CONSENT,
                                            /* fillInIntent */ null,
                                            /* flagsMask */ 0,
                                            /* flagsValues */ 0,
                                            /* extraFlags */ 0,
                                            /* options */ null);
                                    try {
                                        JSONObject error = new JSONObject();
                                        error.put("code", ERROR_CODE_HAS_RESOLUTION);
                                        error.put("message", "Waiting user give permission for fetch friends list, check events.");
                                        callbackContext.error(error);
                                    } catch (JSONException e) {
                                        callbackContext.error("Error while retrieving friends list: " + e.getMessage());
                                    }
                                } catch (IntentSender.SendIntentException err) {
                                    try {
                                        JSONObject error = new JSONObject();
                                        error.put("code", ERROR_CODE_NO_RESOLUTION);
                                        error.put("message", "Error while asking permission for retrieving friends list: " + err.getMessage());
                                        callbackContext.error(error);
                                    } catch (JSONException e) {
                                        callbackContext.error("Error while retrieving friends list: " + e.getMessage());
                                    }
                                }
                            }
                        });
            }
        });
    }

    private void showAnotherPlayersProfileAction(String playerId, final CallbackContext callbackContext) {
        GooglePlayGamesPlugin self = this;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                PlayGames.getPlayersClient(cordova.getActivity())
                    .getCompareProfileIntent(playerId)
                    .addOnSuccessListener(new OnSuccessListener<Intent>() {
                        @Override
                        public void onSuccess(Intent  intent) {
                            cordova.getActivity().startActivityForResult(intent, RC_SHOW_PROFILE);
                            callbackContext.success();
                        }});
            }
        });
    }

    private void getCurrentPlayerStatsAction(final CallbackContext callbackContext) {
        PlayGames.getPlayerStatsClient(cordova.getActivity())
            .loadPlayerStats(true)
            .addOnCompleteListener(new OnCompleteListener<AnnotatedData<PlayerStats>>() {
                @Override
                public void onComplete(@NonNull Task<AnnotatedData<PlayerStats>> task) {
                    if (task.isSuccessful()) {
                        // Check for cached data.
                        if (task.getResult().isStale()) {
                            Log.d(TAG, "Using cached data");
                        }
                        PlayerStats stats = task.getResult().get();
                        JSONObject resultStats = new JSONObject();
                        if (stats != null) {
                            Log.d(TAG, "Player stats loaded");
                            try {
                                resultStats.put("averageSessionLength", stats.getAverageSessionLength());
                                resultStats.put("daysSinceLastPlayed", stats.getDaysSinceLastPlayed());
                                resultStats.put("numberOfPurchases", stats.getNumberOfPurchases());
                                resultStats.put("numberOfSessions", stats.getNumberOfSessions());
                                resultStats.put("sessionPercentile", stats.getSessionPercentile());
                                resultStats.put("spendPercentile", stats.getSpendPercentile());
                                callbackContext.success(resultStats);
                            } catch (JSONException e) {
                                callbackContext.error("Error while creating player stats result object. Error: " + e.getMessage());
                            }
                        }
                    } else {
                        int status = CommonStatusCodes.DEVELOPER_ERROR;
                        if (task.getException() instanceof ApiException) {
                            status = ((ApiException) task.getException()).getStatusCode();
                        }
                        if (task.getException() != null) {
                            callbackContext.error("Failed to fetch Stats Data status: " + status + ": " + task.getException().getMessage());
                        }
                        Log.d(TAG, "Failed to fetch Stats Data status: " + status + ": " + task.getException());
                    }
                }
            });
    }
}
