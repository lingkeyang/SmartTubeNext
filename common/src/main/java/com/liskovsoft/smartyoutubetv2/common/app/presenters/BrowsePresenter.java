package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import com.liskovsoft.mediaserviceinterfaces.MediaGroupManager;
import com.liskovsoft.mediaserviceinterfaces.MediaService;
import com.liskovsoft.mediaserviceinterfaces.SignInManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.auth.SignInData;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Header;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.youtubeapi.service.YouTubeMediaService;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BrowsePresenter implements HeaderPresenter<BrowseView> {
    private static final String TAG = BrowsePresenter.class.getSimpleName();
    private static final long RELOAD_PERIOD_MS = 10*60*1_000;
    @SuppressLint("StaticFieldLeak")
    private static BrowsePresenter sInstance;
    private final Handler mHandler = new Handler();
    private final Context mContext;
    private final PlaybackPresenter mPlaybackPresenter;
    private final DetailsPresenter mDetailsPresenter;
    private final MediaService mMediaService;
    private final ViewManager mViewManager;
    private final OnboardingPresenter mOnboardingPresenter;
    private BrowseView mView;
    private final List<Header> mHeaders;
    private final Map<Integer, Observable<MediaGroup>> mGridMapping;
    private final Map<Integer, Observable<List<MediaGroup>>> mRowMapping;
    private Disposable mUpdateAction;
    private Disposable mScrollAction;
    private long mCurrentHeaderId = -1;
    private long mLastUpdateTimeMs;

    private BrowsePresenter(Context context) {
        GlobalPreferences.instance(context); // auth token storage
        mContext = context;
        mPlaybackPresenter = PlaybackPresenter.instance(context);
        mDetailsPresenter = DetailsPresenter.instance(context);
        mOnboardingPresenter = OnboardingPresenter.instance(context);
        mMediaService = YouTubeMediaService.instance();
        mViewManager = ViewManager.instance(context);
        mHeaders = new ArrayList<>();
        mGridMapping = new HashMap<>();
        mRowMapping = new HashMap<>();
        initHeaders();
    }

    public static BrowsePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new BrowsePresenter(context.getApplicationContext());
        }

        return sInstance;
    }

    @Override
    public void onInitDone() {
        if (mView == null) {
            return;
        }

        addHeaders();
    }

    private void addHeaders() {
        for (Header header : mHeaders) {
            addHeader(header);
        }
    }

    private void addHeader(Header header) {
        mView.updateHeader(VideoGroup.from(header));
    }

    @Override
    public void register(BrowseView view) {
        mView = view;
    }

    @Override
    public void unregister(BrowseView view) {
        mView = null;
    }

    @Override
    public void onVideoItemClicked(Video item) {
        if (mView == null) {
            return;
        }

        mPlaybackPresenter.openVideo(mView, item);
    }

    @Override
    public void onVideoItemLongClicked(Video item) {
        if (mView == null) {
            return;
        }

        mDetailsPresenter.openVideo(item);
    }

    @Override
    public void onScrollEnd(VideoGroup group) {
        Log.d(TAG, "onScrollEnd. Group title: " + group.getTitle());

        boolean updateInProgress = mScrollAction != null && !mScrollAction.isDisposed();

        if (updateInProgress) {
            return;
        }

        continueGroup(group);
    }

    @Override
    public void onViewResumed() {
        long timeAfterPauseMs = System.currentTimeMillis() - mLastUpdateTimeMs;
        if (timeAfterPauseMs > RELOAD_PERIOD_MS) { // update header every n minutes
            if (mCurrentHeaderId != -1) {
                updateHeader(mCurrentHeaderId);
            }
        }
    }

    @Override
    public void onHeaderFocused(long headerId) {
        updateHeader(headerId);
    }

    public void refresh() {
        updateHeader(mCurrentHeaderId);
    }

    private void updateHeader(long headerId) {
        mCurrentHeaderId = headerId;

        if (headerId == -1 || mView == null) {
            return;
        }

        boolean updateInProgress = mUpdateAction != null && !mUpdateAction.isDisposed();

        if (updateInProgress) {
            mUpdateAction.dispose();
        }

        for (Header header : mHeaders) {
            if (header.getId() == headerId) {
                mView.showProgressBar(true);
                mView.clearHeader(header);
                updateHeader(header);
            }
        }
    }

    private void updateHeader(Header header) {
        switch (header.getType()) {
            case Header.TYPE_GRID:
                Observable<MediaGroup> group = mGridMapping.get(header.getId());
                updateGridHeader(header, group, header.isAuthOnly());
                break;
            case Header.TYPE_ROW:
                Observable<List<MediaGroup>> groups = mRowMapping.get(header.getId());
                updateRowsHeader(header, groups, header.isAuthOnly());
                break;
        }
    }

    private void initHeaders() {
        MediaGroupManager mediaGroupManager = mMediaService.getMediaGroupManager();

        mHeaders.add(new Header(MediaGroup.TYPE_HOME, mContext.getString(R.string.title_home), Header.TYPE_ROW, R.drawable.icon_home));
        mHeaders.add(new Header(MediaGroup.TYPE_GAMING, mContext.getString(R.string.title_gaming), Header.TYPE_ROW, R.drawable.icon_gaming));
        mHeaders.add(new Header(MediaGroup.TYPE_NEWS, mContext.getString(R.string.title_news), Header.TYPE_ROW, R.drawable.icon_news));
        mHeaders.add(new Header(MediaGroup.TYPE_MUSIC, mContext.getString(R.string.title_music), Header.TYPE_ROW, R.drawable.icon_music));
        mHeaders.add(new Header(MediaGroup.TYPE_SUBSCRIPTIONS, mContext.getString(R.string.title_subscriptions), Header.TYPE_GRID, R.drawable.icon_subscriptions, true));
        mHeaders.add(new Header(MediaGroup.TYPE_HISTORY, mContext.getString(R.string.title_history), Header.TYPE_GRID, R.drawable.icon_history, true));
        mHeaders.add(new Header(MediaGroup.TYPE_PLAYLISTS, mContext.getString(R.string.title_playlists), Header.TYPE_ROW, R.drawable.icon_playlist, true));

        mRowMapping.put(MediaGroup.TYPE_HOME, mediaGroupManager.getHomeObserve());
        mRowMapping.put(MediaGroup.TYPE_NEWS, mediaGroupManager.getNewsObserve());
        mRowMapping.put(MediaGroup.TYPE_MUSIC, mediaGroupManager.getMusicObserve());
        mRowMapping.put(MediaGroup.TYPE_GAMING, mediaGroupManager.getGamingObserve());
        mRowMapping.put(MediaGroup.TYPE_PLAYLISTS, mediaGroupManager.getPlaylistsObserve());

        mGridMapping.put(MediaGroup.TYPE_SUBSCRIPTIONS, mediaGroupManager.getSubscriptionsObserve());
        mGridMapping.put(MediaGroup.TYPE_HISTORY, mediaGroupManager.getHistoryObserve());
    }

    private void continueGroup(VideoGroup group) {
        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        MediaGroup mediaGroup = group.getMediaGroup();

        MediaGroupManager mediaGroupManager = mMediaService.getMediaGroupManager();

        mScrollAction = mediaGroupManager.continueGroupObserve(mediaGroup)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        continueMediaGroup -> mView.updateHeader(VideoGroup.from(continueMediaGroup, group.getHeader()))
                        , error -> Log.e(TAG, "continueGroup error: " + error)
                        , () -> mView.showProgressBar(false));
    }

    private void updateRowsHeader(Header header, Observable<List<MediaGroup>> groups, boolean authCheck) {
        Log.d(TAG, "loadRowsHeader: Start loading header: " + header.getTitle());

        Observable<List<MediaGroup>> realGroups;

        if (authCheck) {
            SignInManager signInManager = mMediaService.getSignInManager();

            realGroups = signInManager.isSignedObserve()
                    .flatMap(isSigned -> {
                        if (isSigned) {
                            return groups;
                        } else {
                            return Observable.empty();
                        }
                    });
        } else {
            realGroups = groups;
        }

        mUpdateAction = realGroups
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        mediaGroups -> updateRowsHeader(header, mediaGroups)
                        , error -> Log.e(TAG, "loadRowsHeader error: " + error)
                        , () -> {
                            mView.showProgressBar(false);
                            mView.updateHeaderIfEmpty(new SignInData(mContext, header));
                        });
    }

    private void updateRowsHeader(Header header, List<MediaGroup> mediaGroups) {
        for (MediaGroup mediaGroup : mediaGroups) {
            if (mediaGroup.getMediaItems() == null) {
                Log.e(TAG, "loadRowsHeader: MediaGroup is empty. Group Name: " + mediaGroup.getTitle());
                continue;
            }

            mView.updateHeader(VideoGroup.from(mediaGroup, header));

            mLastUpdateTimeMs = System.currentTimeMillis();
        }
    }

    private void updateGridHeader(Header header, Observable<MediaGroup> group, boolean authCheck) {
        Log.d(TAG, "loadGridHeader: Start loading header: " + header.getTitle());

        Observable<MediaGroup> realGroup;

        if (authCheck) {
            SignInManager signInManager = mMediaService.getSignInManager();

            realGroup = signInManager.isSignedObserve()
                    .flatMap(isSigned -> {
                        if (isSigned) {
                            return group;
                        } else {
                            return Observable.empty();
                        }
                    });
        } else {
            realGroup = group;
        }

        mUpdateAction = realGroup
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        mediaGroup -> {
                            mView.updateHeader(VideoGroup.from(mediaGroup, header));
                            mLastUpdateTimeMs = System.currentTimeMillis();
                        }
                        , error -> Log.e(TAG, "loadGridHeader error: " + error)
                        , () -> {
                            mView.showProgressBar(false);
                            mView.updateHeaderIfEmpty(new SignInData(mContext, header));
                        });
    }
}