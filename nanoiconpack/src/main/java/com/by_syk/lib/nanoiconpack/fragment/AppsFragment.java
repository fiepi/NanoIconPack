/*
 * Copyright 2017 By_syk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.by_syk.lib.nanoiconpack.fragment;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.by_syk.lib.nanoiconpack.R;
import com.by_syk.lib.nanoiconpack.bean.AppBean;
import com.by_syk.lib.nanoiconpack.bean.ReqNumBean;
import com.by_syk.lib.nanoiconpack.bean.ResResBean;
import com.by_syk.lib.nanoiconpack.dialog.AppTapHintDialog;
import com.by_syk.lib.nanoiconpack.util.AppFilterReader;
import com.by_syk.lib.nanoiconpack.util.C;
import com.by_syk.lib.nanoiconpack.util.ExtraUtil;
import com.by_syk.lib.nanoiconpack.util.PkgUtil;
import com.by_syk.lib.nanoiconpack.util.RetrofitHelper;
import com.by_syk.lib.nanoiconpack.util.adapter.AppAdapter;
import com.by_syk.lib.nanoiconpack.util.impl.NanoServerService;
import com.by_syk.lib.nanoiconpack.widget.DividerItemDecoration;
import com.by_syk.lib.storage.SP;
import com.by_syk.lib.toast.GlobalToast;
import com.simplecityapps.recyclerview_fastscroll.interfaces.OnFastScrollStateChangeListener;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;

/**
 * Created by By_syk on 2017-01-27.
 */

public class AppsFragment extends Fragment {
    private int pageId = 0;

    private SP sp;

    private View contentView;

    private LinearLayoutManager layoutManager;
    private AppAdapter appAdapter;

    private SwipeRefreshLayout swipeRefreshLayout;

    private LazyLoadTask lazyLoadTask;

    private RetainedFragment retainedFragment;

    private static Handler handler = new Handler();

    private OnLoadDoneListener onLoadDoneListener;

    public interface OnLoadDoneListener {
        void onLoadDone(int pageId, int sum);
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && appAdapter != null && appAdapter.getItemCount() > 0) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isAdded() || lazyLoadTask != null) {
                        return;
                    }
                    lazyLoadTask = new LazyLoadTask();
                    lazyLoadTask.execute(layoutManager.findFirstVisibleItemPosition(),
                            layoutManager.findLastVisibleItemPosition());
                }
            }, 400);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        if (activity instanceof OnLoadDoneListener) {
            onLoadDoneListener = (OnLoadDoneListener) activity;
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (contentView == null) {
            contentView = inflater.inflate(R.layout.fragment_apps, container, false);
            init();

            (new LoadAppsTask()).execute(false);
        }

        return contentView;
    }

    private void init() {
        pageId = getArguments().getInt("pageId");

        sp = new SP(getContext(), false);

        initAdapter();
        initRecycler();
        initSwipeRefresh();
    }

    private void initAdapter() {
        appAdapter = new AppAdapter(getContext());
        appAdapter.setOnItemClickListener(new AppAdapter.OnItemClickListener() {
            @Override
            public void onClick(int pos, AppBean bean) {
                if (!sp.getBoolean("appTapHint1")) {
                    (new AppTapHintDialog()).show(getFragmentManager(), "appTapTintDialog");
                    return;
                }
                if (ExtraUtil.isNetworkConnected(getContext())) {
                    (new SubmitReqTask(pos)).execute();
                } else {
                    GlobalToast.showToast(getContext(), R.string.toast_no_net_no_req);
                }
            }

            @Override
            public void onLongClick(int pos, AppBean bean) {
                if (!sp.getBoolean("appTapHint1")) {
                    (new AppTapHintDialog()).show(getFragmentManager(), "hintDialog");
                    return;
                }
                copyOrShareAppCode(bean, true);
            }
        });
    }

    private void initRecycler() {
        layoutManager = new LinearLayoutManager(getContext());

        FastScrollRecyclerView recyclerView = (FastScrollRecyclerView) contentView.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.addItemDecoration(new DividerItemDecoration(getContext(),
                DividerItemDecoration.VERTICAL));
        recyclerView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (lazyLoadTask == null) {
                        lazyLoadTask = new LazyLoadTask();
                        lazyLoadTask.execute(layoutManager.findFirstVisibleItemPosition(),
                                layoutManager.findLastVisibleItemPosition());
                    }
                } else if (lazyLoadTask != null) {
                    lazyLoadTask.cancel(true);
                    lazyLoadTask = null;
                }
            }
        });
        recyclerView.setStateChangeListener(new OnFastScrollStateChangeListener() {
            @Override
            public void onFastScrollStart() {
                if (lazyLoadTask != null) {
                    lazyLoadTask.cancel(true);
                    lazyLoadTask = null;
                }
            }

            @Override
            public void onFastScrollStop() {
                if (lazyLoadTask == null) {
                    lazyLoadTask = new LazyLoadTask();
                    lazyLoadTask.execute(layoutManager.findFirstVisibleItemPosition(),
                            layoutManager.findLastVisibleItemPosition());
                }
            }
        });

        recyclerView.setAdapter(appAdapter);
    }

    private void initSwipeRefresh() {
        swipeRefreshLayout = (SwipeRefreshLayout) contentView.findViewById(R.id.swipe_refresh_layout);
        swipeRefreshLayout.setColorSchemeColors(getResources().getColor(R.color.color_accent));
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                (new LoadAppsTask()).execute(true);
            }
        });
    }

    private void copyOrShareAppCode(AppBean bean, boolean toCopyOrShare) {
        if (bean == null || bean.getPkgName().equals(bean.getLauncher())) {
            GlobalToast.showToast(getContext(), R.string.toast_code_copy_failed);
            return;
        }

        String label = bean.getLabel();
        String labelEn = PkgUtil.getAppLabelEn(getContext(), bean.getPkgName(), null);
        boolean isSysApp = PkgUtil.isSysApp(getContext(), bean.getPkgName());
        String code = String.format(Locale.US, C.APP_CODE_LABEL, label, labelEn);
        code += "\n" + String.format(Locale.US, C.APP_CODE_COMPONENT, bean.getPkgName(),
                bean.getLauncher(), ExtraUtil.appName2drawableName(label, labelEn));
        if (isSysApp) {
            code = String.format(Locale.US, C.APP_CODE_BUILD, Build.BRAND, Build.MODEL) + "\n" + code;
        }

        if (toCopyOrShare) {
            ExtraUtil.copy2Clipboard(getContext(), code);
            GlobalToast.showToast(getContext(), R.string.toast_code_copied);
        } else {
            ExtraUtil.shareText(getContext(), code, getString(R.string.send_code));
        }
    }

    private class LoadAppsTask extends AsyncTask<Boolean, Integer, List<AppBean>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            retainedFragment = RetainedFragment.initRetainedFragment(getFragmentManager(), "app");
        }

        @Override
        protected List<AppBean> doInBackground(Boolean... booleans) {
            boolean forceRefresh = booleans.length > 0 && booleans[0];
            if (!forceRefresh && retainedFragment.isAppListSaved()) {
                return retainedFragment.getAppList();
            }

            List<AppBean> dataList = new ArrayList<>();
            try {
                PackageManager packageManager = getContext().getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = packageManager.queryIntentActivities(mainIntent, 0);
                for (ResolveInfo resolveInfo : list) {
                    String label = resolveInfo.loadLabel(packageManager).toString();
                    for (String labelPinyin : ExtraUtil.getPinyinForSorting(label)) {
                        AppBean bean = new AppBean();
//                        bean.setIcon(resolveInfo.loadIcon(packageManager));
                        bean.setLabel(label);
                        bean.setLabelPinyin(labelPinyin);
                        bean.setPkgName(resolveInfo.activityInfo.packageName);
                        bean.setLauncher(resolveInfo.activityInfo.name);
                        dataList.add(bean);
                    }
                }

                removeMatched(dataList);
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (dataList.isEmpty()) {
                return dataList;
            }

            Collections.sort(dataList, new Comparator<AppBean>() {
                @Override
                public int compare(AppBean bean1, AppBean bean2) {
//                    return bean1.getAppLabel().compareTo(bean2.getAppLabel());
                    return bean1.getLabelPinyin().compareTo(bean2.getLabelPinyin());
                }
            });

            return dataList;
        }

        @Override
        protected void onPostExecute(List<AppBean> list) {
            super.onPostExecute(list);

            retainedFragment.setAppList(list);

            contentView.findViewById(R.id.view_loading).setVisibility(View.GONE);

            appAdapter.refresh(list);

            swipeRefreshLayout.setRefreshing(false);

            if (getUserVisibleHint()) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded() || lazyLoadTask != null) {
                            return;
                        }
                        lazyLoadTask = new LazyLoadTask();
                        lazyLoadTask.execute(layoutManager.findFirstVisibleItemPosition(),
                                layoutManager.findLastVisibleItemPosition());
                    }
                }, 400);
            }

            if (onLoadDoneListener != null) {
                onLoadDoneListener.onLoadDone(pageId, list.size());
            }
        }

        private void removeMatched(@NonNull List<AppBean> appList) {
            if (appList.isEmpty()) {
                return;
            }

            AppFilterReader reader = AppFilterReader.getInstance();
            reader.init(getResources());
            for (AppFilterReader.Bean bean : reader.getDataList()) {
                if (bean.pkg == null || bean.launcher == null) { // invalid
                    continue;
                }
                Iterator<AppBean> iterator = appList.iterator();
                while (iterator.hasNext()) {
                    AppBean appBean = iterator.next();
                    // Check package name and launcher activity at the same time
                    if (appBean.getPkgName().equals(bean.pkg) && appBean.getLauncher().equals(bean.launcher)) {
                        iterator.remove();
                        // To remove all polyphone items, cannot use break
//                        break;
                    }
                }
            }
        }
    }

    private class LazyLoadTask extends AsyncTask<Integer, Integer, Boolean> {
        @Override
        protected Boolean doInBackground(Integer... pos) {
            if (!isAdded() || pos == null || pos.length < 2) {
                return false;
            }

            PackageManager packageManager = getContext().getPackageManager();
            for (int i = pos[0]; i <= pos[1]; ++i) {
                if (isCancelled() || !isAdded()) {
                    return false;
                }
                AppBean bean = appAdapter.getItem(i);
                if (bean == null || bean.getIcon() != null) {
                    continue;
                }
//                Drawable icon = PkgUtil.getIcon(packageManager, bean.getPkgName());
                Drawable icon = PkgUtil.getIcon(packageManager, bean.getPkgName(), bean.getLauncher());
                if (icon != null) {
                    bean.setIcon(icon);
                    publishProgress(i);
                }
            }

            if (!ExtraUtil.isNetworkConnected(getContext())) {
                return false;
            }
            String deviceId = ExtraUtil.getDeviceId(getContext());
            NanoServerService nanoServerService = null;
            for (int i = pos[0]; i <= pos[1]; ++i) {
                if (isCancelled() || !isAdded()) {
                    return false;
                }
                AppBean bean = appAdapter.getItem(i);
                if (bean == null || bean.getReqTimes() >= 0) {
                    continue;
                }
                if (nanoServerService == null) {
                    nanoServerService = RetrofitHelper.getInstance()
                            .getService(NanoServerService.class);
                }
                Call<ResResBean<ReqNumBean>> call = nanoServerService
                        .getReqNum(getContext().getPackageName(), bean.getPkgName(), deviceId);
                try {
                    ResResBean<ReqNumBean> resResBean = call.execute().body();
                    if (resResBean.isStatusSuccess()) {
                        ReqNumBean reqNumBean = resResBean.getResult();
                        if (reqNumBean != null) {
                            bean.setReqTimes(reqNumBean.getReqTimes());
                            bean.setMark(reqNumBean.isRequested());
                            publishProgress(i);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return false;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);

            appAdapter.notifyItemChanged(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            super.onPostExecute(aBoolean);

            lazyLoadTask = null;
        }
    }

    private class SubmitReqTask extends AsyncTask<String, Integer, Boolean> {
        private int pos;

        SubmitReqTask(int pos) {
            this.pos = pos;
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            AppBean bean = appAdapter.getItem(pos);
            if (bean == null || bean.getPkgName().equals(bean.getLauncher())) {
                return false;
            }
            if (bean.isMark()) {
                return true;
            }

            String labelEn = PkgUtil.getAppLabelEn(getContext(), bean.getPkgName(), "");
            Map<String, String> map = new HashMap<>();
            map.put("icon", ExtraUtil.appName2drawableName(bean.getLabel(), labelEn));
            map.put("label", bean.getLabel());
            map.put("labelEn", labelEn);
            map.put("pkg", bean.getPkgName());
            map.put("launcher", bean.getLauncher());
            map.put("sysApp", PkgUtil.isSysApp(getContext(), bean.getPkgName()) ? "1" : "0");
            map.put("deviceId", ExtraUtil.getDeviceId(getContext()));
            map.put("deviceBrand", Build.BRAND);
            map.put("deviceModel", Build.MODEL);
            map.put("deviceSdk", String.valueOf(Build.VERSION.SDK_INT));

            NanoServerService nanoServerService = RetrofitHelper.getInstance()
                    .getService(NanoServerService.class);
            Call<ResResBean<Integer>> call = nanoServerService.reqRedraw(getContext().getPackageName(), map);
            try {
                ResResBean<Integer> resResBean = call.execute().body();
                if (resResBean != null && (resResBean.getStatus() == ResResBean.STATUS_SUCCESS
                        || resResBean.getStatus() == ResResBean.STATUS_EXISTED)) {
                    bean.setReqTimes(resResBean.getResult());
                    bean.setMark(true);
                    publishProgress();
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return false;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);

            appAdapter.notifyItemChanged(pos);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);

            if (!isAdded()) {
                return;
            }

            GlobalToast.showToast(getContext(), result ? R.string.toast_icon_reqed
                    : R.string.toast_icon_req_failed);
        }
    }

    public static AppsFragment newInstance(int id) {
        AppsFragment fragment = new AppsFragment();

        Bundle bundle = new Bundle();
        bundle.putInt("pageId", id);
        fragment.setArguments(bundle);

        return fragment;
    }
}
