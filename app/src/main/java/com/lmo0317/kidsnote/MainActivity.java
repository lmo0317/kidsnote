package com.lmo0317.kidsnote;

import android.app.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity {
  private static final int STORAGE_PERMISSION_REQUEST = 1001;
  private static final long DISK_CACHE_LIMIT = 192L * 1024 * 1024;
  private static final int MEMORY_CACHE_LIMIT = (int) Math.max(16L * 1024 * 1024,
      Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6));
  private final ExecutorService networkIo = Executors.newFixedThreadPool(2);
  private final ThreadPoolExecutor thumbnailIo = new ThreadPoolExecutor(
      3, 3, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(192), new ThreadPoolExecutor.DiscardOldestPolicy());
  private final ArrayList<Photo> photos = new ArrayList<>();
  private final ArrayList<Photo> savedPhotos = new ArrayList<>();
  private final ArrayList<ScheduleItem> schedules = new ArrayList<>();
  private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(MEMORY_CACHE_LIMIT) {
    @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
  };
  private TextView countText, galleryTitle, loginStatusBadge, loginStatusDetail, previewTab, savedTab, savedCountText,
      albumNavButton, scheduleNavButton, scheduleCountText;
  private ProgressBar progress;
  private GridView gallery, savedGallery;
  private ScaleGestureDetector galleryScaleDetector;
  private int galleryColumns = 3;
  private float galleryScale = 1f;
  private Spinner yearSpinner, savedYearSpinner, scheduleYearSpinner;
  private ListView scheduleList;
  private Button downloadButton;
  private LinearLayout webPanel, emptyState, savedEmptyState, previewTabContent, savedTabContent,
      albumPage, schedulePage, scheduleEmptyState;
  private WebView webView;
  private volatile String childId = "", enrollment = "", loginId = "";
  private Photo pendingSingleSave;
  private Button pendingSingleSaveButton;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean sessionChecking = false, pendingLoad = false, resettingLogin = false,
      loginVerificationStarted = false;
  private volatile int loadGeneration = 0;
  private final Runnable sessionTimeout = () -> {
    if (!childId.isEmpty()) return;
    sessionChecking = false;
    if (pendingLoad) {
      pendingLoad = false;
      setLoading(false, "로그인이 필요합니다");
      Toast.makeText(this, "먼저 로그인 버튼으로 키즈노트를 연결해 주세요.", Toast.LENGTH_LONG).show();
    }
    showDisconnected();
  };

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    applySystemBarInsets();
    countText = findViewById(R.id.countText);
    galleryTitle = findViewById(R.id.galleryTitle);
    loginStatusBadge = findViewById(R.id.loginStatusBadge);
    loginStatusDetail = findViewById(R.id.loginStatusDetail);
    progress = findViewById(R.id.progress);
    gallery = findViewById(R.id.gallery);
    yearSpinner = findViewById(R.id.yearSpinner);
    downloadButton = findViewById(R.id.downloadButton);
    webPanel = findViewById(R.id.webPanel);
    emptyState = findViewById(R.id.emptyState);
    previewTab = findViewById(R.id.previewTab);
    savedTab = findViewById(R.id.savedTab);
    savedCountText = findViewById(R.id.savedCountText);
    savedGallery = findViewById(R.id.savedGallery);
    savedYearSpinner = findViewById(R.id.savedYearSpinner);
    savedEmptyState = findViewById(R.id.savedEmptyState);
    previewTabContent = findViewById(R.id.previewTabContent);
    savedTabContent = findViewById(R.id.savedTabContent);
    albumPage = findViewById(R.id.albumPage);
    schedulePage = findViewById(R.id.schedulePage);
    scheduleEmptyState = findViewById(R.id.scheduleEmptyState);
    albumNavButton = findViewById(R.id.albumNavButton);
    scheduleNavButton = findViewById(R.id.scheduleNavButton);
    scheduleCountText = findViewById(R.id.scheduleCountText);
    scheduleYearSpinner = findViewById(R.id.scheduleYearSpinner);
    scheduleList = findViewById(R.id.scheduleList);
    webView = findViewById(R.id.webView);
    loginId = getPreferences(MODE_PRIVATE).getString("login_id", "");
    galleryColumns = Math.max(2, Math.min(5, getPreferences(MODE_PRIVATE).getInt("gallery_columns", 3)));

    ArrayList<String> years = new ArrayList<>();
    int current = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = current; y >= 2022; y--) years.add(y + "년");
    yearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years));
    scheduleYearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years));
    ArrayList<String> savedYears = new ArrayList<>();
    savedYears.add("전체 연도");
    savedYears.addAll(years);
    savedYearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, savedYears));
    savedYearSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (savedTabContent != null && savedTabContent.getVisibility() == View.VISIBLE) loadSavedPhotos();
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });

    setupWebView();
    restoreSessionSilently();
    findViewById(R.id.loginButton).setOnClickListener(v -> openLogin());
    findViewById(R.id.closeWebButton).setOnClickListener(v -> webPanel.setVisibility(View.GONE));
    findViewById(R.id.loadButton).setOnClickListener(v -> loadYear());
    downloadButton.setOnClickListener(v -> confirmBackup());
    gallery.setAdapter(new PhotoAdapter(photos, gallery));
    savedGallery.setAdapter(new PhotoAdapter(savedPhotos, savedGallery));
    gallery.setNumColumns(galleryColumns);
    savedGallery.setNumColumns(galleryColumns);
    gallery.setOnItemClickListener((parent, view, position, id) -> {
      if (position >= 0 && position < photos.size()) showPhoto(position);
    });
    savedGallery.setOnItemClickListener((parent, view, position, id) -> {
      if (position >= 0 && position < savedPhotos.size()) showPhoto(savedPhotos, position, false);
    });
    previewTab.setOnClickListener(v -> showTab(false));
    savedTab.setOnClickListener(v -> showTab(true));
    albumNavButton.setOnClickListener(v -> showMainPage(false));
    scheduleNavButton.setOnClickListener(v -> showMainPage(true));
    findViewById(R.id.analyzeScheduleButton).setOnClickListener(v -> loadSchedules());
    scheduleList.setAdapter(new ScheduleAdapter());
    setupGalleryPinch();
    networkIo.execute(this::trimThumbnailDiskCache);
  }

  private void applySystemBarInsets() {
    View root = findViewById(R.id.rootView);
    applyInsets(root);
  }

  private void setupGalleryPinch() {
    galleryScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
        galleryScale = 1f;
        return true;
      }

      @Override public boolean onScale(ScaleGestureDetector detector) {
        galleryScale *= detector.getScaleFactor();
        if (galleryScale >= 1.18f && galleryColumns > 2) {
          setGalleryColumns(galleryColumns - 1);
          galleryScale = 1f;
        } else if (galleryScale <= .84f && galleryColumns < 5) {
          setGalleryColumns(galleryColumns + 1);
          galleryScale = 1f;
        }
        return true;
      }
    });
    View.OnTouchListener pinchListener = new View.OnTouchListener() {
      private boolean pinching;
      @Override public boolean onTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
          pinching = true;
          view.getParent().requestDisallowInterceptTouchEvent(true);
        }
        galleryScaleDetector.onTouchEvent(event);
        boolean consume = pinching;
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
          pinching = false;
          view.getParent().requestDisallowInterceptTouchEvent(false);
        }
        return consume;
      }
    };
    gallery.setOnTouchListener(pinchListener);
    savedGallery.setOnTouchListener(pinchListener);
  }

  private void setGalleryColumns(int columns) {
    columns = Math.max(2, Math.min(5, columns));
    if (columns == galleryColumns) return;
    int firstVisible = gallery.getFirstVisiblePosition();
    galleryColumns = columns;
    gallery.setNumColumns(columns);
    savedGallery.setNumColumns(columns);
    ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
    gallery.post(() -> gallery.setSelection(Math.max(0, firstVisible)));
    getPreferences(MODE_PRIVATE).edit().putInt("gallery_columns", columns).apply();
  }

  private int galleryCellSize() {
    return galleryCellSize(gallery);
  }

  private int galleryCellSize(GridView target) {
    int width = target.getWidth();
    if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
    return Math.max(dp(68), (width - dp(8) - dp(3) * (galleryColumns - 1)) / galleryColumns);
  }

  private void showTab(boolean saved) {
    findViewById(R.id.appHeader).setVisibility(View.VISIBLE);
    previewTabContent.setVisibility(saved ? View.GONE : View.VISIBLE);
    savedTabContent.setVisibility(saved ? View.VISIBLE : View.GONE);
    previewTab.setBackgroundResource(saved ? R.drawable.bg_tab_unselected : R.drawable.bg_tab_selected);
    savedTab.setBackgroundResource(saved ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
    previewTab.setTextColor(getColor(saved ? R.color.text_secondary : R.color.mint_dark));
    savedTab.setTextColor(getColor(saved ? R.color.mint_dark : R.color.text_secondary));
    if (saved) loadSavedPhotos();
  }

  private void showMainPage(boolean schedule) {
    albumPage.setVisibility(schedule ? View.GONE : View.VISIBLE);
    schedulePage.setVisibility(schedule ? View.VISIBLE : View.GONE);
    albumNavButton.setTextColor(getColor(schedule ? R.color.text_secondary : R.color.mint_dark));
    scheduleNavButton.setTextColor(getColor(schedule ? R.color.mint_dark : R.color.text_secondary));
  }

  private void loadSchedules() {
    if (childId.isEmpty()) {
      Toast.makeText(this, "먼저 키즈노트 계정을 연결해 주세요.", Toast.LENGTH_LONG).show();
      return;
    }
    String targetYear = scheduleYearSpinner.getSelectedItem().toString().replace("년", "");
    Button button = findViewById(R.id.analyzeScheduleButton);
    button.setEnabled(false);
    button.setText("분석 중…");
    scheduleCountText.setText(targetYear + "년 알림장에서 날짜와 시간을 찾는 중");
    scheduleEmptyState.setVisibility(View.VISIBLE);
    networkIo.execute(() -> {
      LinkedHashMap<String, ScheduleItem> found = new LinkedHashMap<>();
      String errorMessage = null;
      try {
        String next = "https://www.kidsnote.com/api/v1_2/children/" + childId + "/reports/?page_size=5000";
        for (int page = 0; next != null && page < 50; page++) {
          JSONObject payload = requestJson(next);
          JSONArray items = payload.optJSONArray("results");
          if (items == null) items = payload.optJSONArray("reports");
          if (items == null) items = payload.optJSONArray("data");
          if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) extractSchedules(item, targetYear, found);
          }
          next = payload.isNull("next") ? null : payload.optString("next", null);
          if (next != null && next.isEmpty()) next = null;
        }
      } catch (Exception error) {
        errorMessage = error.getMessage();
      }
      String failure = errorMessage;
      runOnUiThread(() -> {
        schedules.clear();
        schedules.addAll(found.values());
        schedules.sort(Comparator.comparing((ScheduleItem item) -> item.date).thenComparing(item -> item.time));
        ((BaseAdapter) scheduleList.getAdapter()).notifyDataSetChanged();
        button.setEnabled(true);
        button.setText("일정 불러오기");
        if (failure != null) scheduleCountText.setText("불러오기 실패: " + failure);
        else scheduleCountText.setText(schedules.isEmpty() ? targetYear + "년 명시된 일정을 찾지 못했습니다"
            : targetYear + "년 일정 " + schedules.size() + "개 · 날짜순");
        scheduleEmptyState.setVisibility(schedules.isEmpty() ? View.VISIBLE : View.GONE);
      });
    });
  }

  private void extractSchedules(JSONObject item, String targetYear, Map<String, ScheduleItem> found) {
    String written = first(item, "date_written", "created", "created_at");
    String raw = collectScheduleText(item);
    String text = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    if (text.length() < 4) return;
    String itemTitle = first(item, "title", "subject", "name");
    Pattern fullDate = Pattern.compile("(20\\d{2})\\s*[년./-]\\s*(1[0-2]|0?[1-9])\\s*[월./-]\\s*(3[01]|[12]\\d|0?[1-9])\\s*일?");
    Matcher full = fullDate.matcher(text);
    while (full.find()) addScheduleMatch(text, itemTitle, written, full.start(), full.end(),
        full.group(1), full.group(2), full.group(3), targetYear, found);

    Pattern monthDay = Pattern.compile("(?<!\\d)(1[0-2]|0?[1-9])\\s*월\\s*(3[01]|[12]\\d|0?[1-9])\\s*일");
    Matcher shortDate = monthDay.matcher(text);
    while (shortDate.find()) {
      if (fullDate.matcher(text.substring(Math.max(0, shortDate.start() - 8), shortDate.end())).find()) continue;
      String inferredYear = inferEventYear(written, shortDate.group(1));
      addScheduleMatch(text, itemTitle, written, shortDate.start(), shortDate.end(), inferredYear,
          shortDate.group(1), shortDate.group(2), targetYear, found);
    }
  }

  private void addScheduleMatch(String text, String itemTitle, String written, int start, int end,
      String year, String month, String day, String targetYear, Map<String, ScheduleItem> found) {
    if (!targetYear.equals(year)) return;
    int from = Math.max(0, Math.max(text.lastIndexOf('.', start), text.lastIndexOf('\n', start)) + 1);
    int period = text.indexOf('.', end);
    int to = period < 0 ? Math.min(text.length(), end + 70) : Math.min(text.length(), period + 1);
    String context = text.substring(from, to).trim();
    if (context.length() < 4) return;
    String date = String.format(Locale.US, "%04d-%02d-%02d", Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
    Matcher timeMatcher = Pattern.compile("(?:(오전|오후)\\s*)?([01]?\\d|2[0-3])\\s*시(?:\\s*([0-5]?\\d)\\s*분)?").matcher(context);
    String time = "";
    if (timeMatcher.find()) {
      int hour = Integer.parseInt(timeMatcher.group(2));
      if ("오후".equals(timeMatcher.group(1)) && hour < 12) hour += 12;
      if ("오전".equals(timeMatcher.group(1)) && hour == 12) hour = 0;
      time = String.format(Locale.US, "%02d:%02d", hour,
          timeMatcher.group(3) == null ? 0 : Integer.parseInt(timeMatcher.group(3)));
    }
    String title = itemTitle.trim().isEmpty() ? context : itemTitle.trim();
    if (title.length() > 72) title = title.substring(0, 72) + "…";
    String key = date + "|" + title.replaceAll("\\s+", "").toLowerCase(Locale.KOREA);
    found.putIfAbsent(key, new ScheduleItem(date, time, title, context, written));
  }

  private static String inferEventYear(String written, String month) {
    String year = yearOf(written);
    Matcher writtenMonth = Pattern.compile("20\\d{2}-(\\d{2})").matcher(written == null ? "" : written);
    if (year.isEmpty()) year = Integer.toString(Calendar.getInstance().get(Calendar.YEAR));
    if (writtenMonth.find() && Integer.parseInt(writtenMonth.group(1)) >= 11 && Integer.parseInt(month) <= 2)
      year = Integer.toString(Integer.parseInt(year) + 1);
    return year;
  }

  private static String collectScheduleText(JSONObject item) {
    StringBuilder out = new StringBuilder();
    String[] keys = {"title", "subject", "content", "contents", "description", "body", "text", "memo", "notice", "message"};
    for (String key : keys) appendJsonText(item.opt(key), out, 0);
    return out.toString();
  }

  private static void appendJsonText(Object value, StringBuilder out, int depth) {
    if (value == null || value == JSONObject.NULL || depth > 4) return;
    if (value instanceof String) out.append(' ').append(value);
    else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) appendJsonText(array.opt(i), out, depth + 1);
    } else if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) appendJsonText(object.opt(keys.next()), out, depth + 1);
    }
  }

  private void loadSavedPhotos() {
    String selected = savedYearSpinner.getSelectedItem() == null ? "전체 연도" : savedYearSpinner.getSelectedItem().toString();
    String year = selected.replace("년", "");
    boolean allYears = selected.equals("전체 연도");
    savedCountText.setText((allYears ? "전체" : year + "년") + " 사진을 확인하는 중");
    networkIo.execute(() -> {
      ArrayList<Photo> found = new ArrayList<>();
      Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
      String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
          MediaStore.Images.Media.DISPLAY_NAME};
      String selection;
      String[] arguments;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        arguments = new String[]{allYears ? "Pictures/KidsNote/%" : "Pictures/KidsNote/" + year + "%"};
      } else {
        selection = MediaStore.Images.Media.DATA + " LIKE ?";
        arguments = new String[]{allYears ? "%/Pictures/KidsNote/%" : "%/Pictures/KidsNote/" + year + "/%"};
      }
      try (Cursor cursor = getContentResolver().query(collection, projection, selection, arguments,
          MediaStore.Images.Media.DATE_ADDED + " DESC")) {
        if (cursor != null) {
          int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
          int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
          while (cursor.moveToNext()) {
            long id = cursor.getLong(idColumn);
            long seconds = cursor.getLong(dateColumn);
            Uri uri = Uri.withAppendedPath(collection, Long.toString(id));
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date(seconds * 1000L));
            found.add(new Photo(uri.toString(), date));
          }
        }
      } catch (Exception ignored) {}
      runOnUiThread(() -> {
        savedPhotos.clear();
        savedPhotos.addAll(found);
        ((BaseAdapter) savedGallery.getAdapter()).notifyDataSetChanged();
        savedCountText.setText(found.isEmpty() ? (allYears ? "저장된 사진이 없습니다" : year + "년 저장 사진이 없습니다")
            : (allYears ? "전체 " : year + "년 ") + found.size() + "장 · 눌러서 크게 보기");
        savedEmptyState.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
      });
    });
  }

  private void applyInsets(View target) {
    target.setOnApplyWindowInsetsListener((view, windowInsets) -> {
      int left, top, right, bottom;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.graphics.Insets bars = windowInsets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
      } else {
        left = windowInsets.getSystemWindowInsetLeft();
        top = windowInsets.getSystemWindowInsetTop();
        right = windowInsets.getSystemWindowInsetRight();
        bottom = windowInsets.getSystemWindowInsetBottom();
      }
      view.setPadding(left, top, right, bottom);
      return windowInsets;
    });
    target.requestApplyInsets();
  }

  private void setupWebView() {
    android.webkit.CookieManager.getInstance().setAcceptCookie(true);
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    webView.addJavascriptInterface(new LoginIdBridge(), "KidsNoteGallery");
    webView.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        Matcher matcher = Pattern.compile("/children/(\\d+)/(reports|albums)").matcher(url);
        if (matcher.find()) {
          childId = matcher.group(1);
          String header = request.getRequestHeaders().get("X-Enrollment");
          if (header != null) enrollment = header;
          runOnUiThread(() -> {
            mainHandler.removeCallbacks(sessionTimeout);
            sessionChecking = false;
            showConnected();
            webPanel.setVisibility(View.GONE);
            if (pendingLoad) {
              pendingLoad = false;
              setLoading(false, "로그인 확인 완료");
              loadYear();
            }
          });
        }
        return super.shouldInterceptRequest(view, request);
      }
      @Override public void onPageFinished(WebView view, String url) {
        if (resettingLogin) return;
        installLoginNavigationCapture();
        if (url.matches(".*\\/(?:[a-z]{2}\\/)?login(?:[/?].*)?$")) {
          webView.setVisibility(View.VISIBLE);
          installLoginIdCapture();
          if (webPanel.getVisibility() != View.VISIBLE) {
            mainHandler.removeCallbacks(sessionTimeout);
            sessionChecking = false;
            showDisconnected();
          }
        } else {
          if (!url.contains("/service/report")) view.loadUrl("https://www.kidsnote.com/service/report");
          else if (!childId.isEmpty()) webPanel.setVisibility(View.GONE);
        }
      }
    });
  }

  private void openLogin() {
    mainHandler.removeCallbacks(sessionTimeout);
    sessionChecking = false;
    pendingLoad = false;
    resettingLogin = true;
    loginVerificationStarted = false;
    childId = "";
    enrollment = "";
    loginId = "";
    getPreferences(MODE_PRIVATE).edit().remove("login_id").apply();
    webView.stopLoading();
    webView.clearHistory();
    webView.clearCache(true);
    webView.setVisibility(View.INVISIBLE);
    WebStorage.getInstance().deleteAllData();
    loginStatusDetail.setText("새 로그인 화면을 준비하는 중입니다");
    webPanel.setVisibility(View.VISIBLE);
    android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
    cookieManager.removeAllCookies(removed -> {
      cookieManager.flush();
      runOnUiThread(() -> {
        resettingLogin = false;
        loginStatusDetail.setText("로그인 화면에서 키즈노트 계정을 연결해 주세요");
        webView.loadUrl("https://www.kidsnote.com/login");
      });
    });
  }

  private void installLoginIdCapture() {
    webView.evaluateJavascript("(function(){" +
        "if(window.__kidsNoteIdCapture)return;window.__kidsNoteIdCapture=true;" +
        "function bind(){document.querySelectorAll('input[type=email],input[autocomplete=username],input[name*=email i],input[name*=username i],input[name*=login i]').forEach(function(el){" +
        "if(el.__kidsNoteBound)return;el.__kidsNoteBound=true;" +
        "function send(){if(el.value)window.KidsNoteGallery.captureLoginId(el.value);}" +
        "el.addEventListener('input',send);el.addEventListener('change',send);send();});}" +
        "bind();new MutationObserver(bind).observe(document.documentElement,{childList:true,subtree:true});" +
        "})();", null);
  }

  private void installLoginNavigationCapture() {
    webView.evaluateJavascript("(function(){" +
        "if(window.__kidsNoteNavigationCapture)return;window.__kidsNoteNavigationCapture=true;" +
        "var last=location.href;function report(){var now=location.href;if(now!==last){last=now;window.KidsNoteGallery.captureNavigation(now);}}" +
        "setInterval(report,250);window.addEventListener('popstate',report);window.addEventListener('hashchange',report);" +
        "})();", null);
  }

  private void verifyLoginAfterNavigation(String url) {
    if (resettingLogin || loginVerificationStarted || url == null ||
        url.matches(".*\\/(?:[a-z]{2}\\/)?login(?:[/?#].*)?$")) return;
    loginVerificationStarted = true;
    sessionChecking = true;
    loginStatusDetail.setText("로그인 완료를 확인하는 중입니다");
    webView.loadUrl("https://www.kidsnote.com/service/report");
  }

  private class LoginIdBridge {
    @JavascriptInterface public void captureLoginId(String value) {
      if (value == null) return;
      String cleaned = value.trim();
      if (cleaned.isEmpty() || cleaned.length() > 100) return;
      loginId = cleaned;
      getPreferences(MODE_PRIVATE).edit().putString("login_id", cleaned).apply();
    }

    @JavascriptInterface public void captureNavigation(String url) {
      runOnUiThread(() -> verifyLoginAfterNavigation(url));
    }
  }

  private void restoreSessionSilently() {
    sessionChecking = true;
    loginStatusDetail.setText("저장된 로그인 상태를 확인하는 중입니다");
    webPanel.setVisibility(View.GONE);
    webView.loadUrl("https://www.kidsnote.com/service/report");
    mainHandler.removeCallbacks(sessionTimeout);
    mainHandler.postDelayed(sessionTimeout, 8000);
  }

  private String selectedYear() {
    return yearSpinner.getSelectedItem().toString().replace("년", "");
  }

  private void loadYear() {
    if (childId.isEmpty()) {
      if (sessionChecking) {
        pendingLoad = true;
        setLoading(true, "로그인 상태를 확인하는 중");
      } else {
        Toast.makeText(this, "먼저 로그인 버튼으로 키즈노트를 연결해 주세요.", Toast.LENGTH_LONG).show();
      }
      return;
    }
    final String year = selectedYear();
    final int generation = ++loadGeneration;
    thumbnailIo.getQueue().clear();
    photos.clear();
    ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
    galleryTitle.setText(year + "년 사진");
    emptyState.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    setLoading(true, year + "년 사진 목록을 불러오는 중");
    networkIo.execute(() -> {
      try {
        LinkedHashMap<String, Photo> found = new LinkedHashMap<>();
        loadCollection("reports", year, found);
        loadCollection("albums", year, found);
        runOnUiThread(() -> {
          if (generation != loadGeneration) return;
          photos.clear(); photos.addAll(found.values());
          photos.sort((left, right) -> right.date.compareTo(left.date));
          ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
          galleryTitle.setText(year + "년 사진");
          countText.setText(photos.isEmpty() ? "가져온 사진이 없습니다" : photos.size() + "장의 사진 · 눌러서 크게 보기");
          emptyState.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
          downloadButton.setEnabled(!photos.isEmpty());
          setLoading(false, photos.isEmpty() ? "사진이 없습니다" : photos.size() + "장의 사진을 가져왔습니다");
        });
      } catch (Exception error) {
        runOnUiThread(() -> { if (generation == loadGeneration) setLoading(false, "불러오기 실패: " + error.getMessage()); });
      }
    });
  }

  private void loadCollection(String type, String year, Map<String, Photo> found) throws Exception {
    String next = "https://www.kidsnote.com/api/v1_2/children/" + childId + "/" + type + "/?page_size=5000";
    for (int page = 0; next != null && page < 50; page++) {
      JSONObject payload = requestJson(next);
      JSONArray items = payload.optJSONArray("results");
      if (items == null) items = payload.optJSONArray(type);
      if (items == null) items = payload.optJSONArray("data");
      if (items != null) for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null) continue;
        String date = type.equals("albums")
            ? first(item, "created")
            : first(item, "date_written");
        if (!year.equals(yearOf(date))) continue;
        collectAttachedImages(item, date, found);
      }
      next = payload.isNull("next") ? null : payload.optString("next", null);
      if (next != null && next.isEmpty()) next = null;
    }
  }

  private JSONObject requestJson(String url) throws Exception {
    HttpURLConnection connection = connection(url);
    connection.setRequestProperty("Accept", "application/json");
    int code = connection.getResponseCode();
    if (code == 401 || code == 403) throw new IOException("로그인이 만료되었습니다.");
    if (code < 200 || code >= 300) throw new IOException("키즈노트 응답 " + code);
    try (InputStream in = connection.getInputStream()) {
      return new JSONObject(readText(in));
    }
  }

  private HttpURLConnection connection(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
    connection.setRequestProperty("Cookie", android.webkit.CookieManager.getInstance().getCookie("https://www.kidsnote.com"));
    connection.setRequestProperty("User-Agent", "KidsNoteGallery-Android/1.0");
    if (!enrollment.isEmpty()) connection.setRequestProperty("X-Enrollment", enrollment);
    return connection;
  }

  private static String yearOf(String date) {
    Matcher matcher = Pattern.compile("(?:^|\\D)(20\\d{2})(?:\\D|$)").matcher(date == null ? "" : date);
    return matcher.find() ? matcher.group(1) : "";
  }

  private void collectAttachedImages(JSONObject item, String date, Map<String, Photo> found) throws JSONException {
    String[] arrayKeys = {"attached_images", "images", "photos", "image_files", "attachments"};
    for (String key : arrayKeys) {
      JSONArray images = item.optJSONArray(key);
      if (images == null) continue;
      for (int i = 0; i < images.length(); i++) addBestImage(images.opt(i), date, found);
    }
    String[] singleKeys = {"attached_image", "image", "photo"};
    for (String key : singleKeys) if (item.has(key)) addBestImage(item.opt(key), date, found);
  }

  private void addBestImage(Object value, String date, Map<String, Photo> found) {
    String url = "";
    if (value instanceof String) url = (String) value;
    else if (value instanceof JSONObject) {
      JSONObject image = (JSONObject) value;
      url = first(image, "original", "large", "url", "file", "image", "thumbnail");
    }
    if (!url.startsWith("http") || !url.matches("(?i).*(jpg|jpeg|png|webp|gif|heic)(\\?.*)?$")) return;
    String key = url.replaceAll("\\?.*$", "").replaceAll("(?i)/(thumb|thumbnail|small|medium|large|original)/", "/");
    found.putIfAbsent(key, new Photo(url, date));
  }

  private byte[] downloadBytes(String url) throws Exception {
    InputStream source;
    if (url.startsWith("content://")) source = getContentResolver().openInputStream(Uri.parse(url));
    else {
      HttpURLConnection connection = connection(url);
      connection.setRequestProperty("Accept", "image/*");
      source = connection.getInputStream();
    }
    if (source == null) throw new IOException("사진을 열 수 없습니다.");
    try (InputStream input = source) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[16384]; int read;
      while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
      return output.toByteArray();
    }
  }

  private Bitmap decodeImage(byte[] data, int maxDimension) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    if (maxDimension > 0) {
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, options);
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= maxDimension) options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      options.inPreferredConfig = Bitmap.Config.RGB_565;
    }
    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (bitmap == null) throw new IOException("이미지를 읽을 수 없습니다.");
    return bitmap;
  }

  private Bitmap loadThumbnail(String url, int generation) throws Exception {
    Bitmap cached = thumbnailCache.get(url);
    if (cached != null) return cached;
    File diskFile = thumbnailFile(url);
    if (diskFile.isFile()) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inPreferredConfig = Bitmap.Config.RGB_565;
      Bitmap diskBitmap = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), options);
      if (diskBitmap != null) {
        diskFile.setLastModified(System.currentTimeMillis());
        if (generation != loadGeneration) { diskBitmap.recycle(); return null; }
        thumbnailCache.put(url, diskBitmap);
        return diskBitmap;
      }
      diskFile.delete();
    }
    Bitmap bitmap = decodeImage(downloadBytes(url), 320);
    if (generation != loadGeneration) {
      bitmap.recycle();
      return null;
    }
    thumbnailCache.put(url, bitmap);
    File directory = diskFile.getParentFile();
    if ((directory.isDirectory() || directory.mkdirs()) && !diskFile.exists()) {
      try (OutputStream output = new BufferedOutputStream(new FileOutputStream(diskFile))) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output);
      } catch (IOException ignored) {}
    }
    return bitmap;
  }

  private File thumbnailFile(String url) throws NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder name = new StringBuilder(64);
    for (byte value : digest) name.append(String.format(Locale.US, "%02x", value & 0xff));
    return new File(new File(getCacheDir(), "thumbnails"), name + ".jpg");
  }

  private void trimThumbnailDiskCache() {
    File directory = new File(getCacheDir(), "thumbnails");
    File[] files = directory.listFiles();
    if (files == null) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    long total = 0;
    for (File file : files) total += file.length();
    for (File file : files) {
      if (total <= DISK_CACHE_LIMIT) break;
      long length = file.length();
      if (file.delete()) total -= length;
    }
  }

  private Bitmap loadOriginal(String url, int maxDimension) throws Exception {
    return decodeImage(downloadBytes(url), maxDimension);
  }

  private void saveAll() {
    if (photos.isEmpty()) return;
    pendingSingleSave = null;
    pendingSingleSaveButton = null;
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
      return;
    }
    progress.setMax(photos.size()); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    networkIo.execute(() -> {
      int saved = 0;
      for (int i = 0; i < photos.size(); i++) {
        Bitmap bitmap = null;
        try {
          bitmap = loadOriginal(photos.get(i).url, 0);
          String fileName = "kidsnote_" + selectedYear() + "_" + String.format(Locale.US, "%04d", i + 1) + ".jpg";
          writeBitmapToGallery(bitmap, fileName, selectedYear());
          saved++;
        } catch (Exception ignored) {
        } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
        int done = i + 1;
        runOnUiThread(() -> progress.setProgress(done));
      }
      int finalSaved = saved;
      runOnUiThread(() -> {
        progress.setVisibility(View.GONE); downloadButton.setEnabled(true);
        Toast.makeText(this, "Pictures/KidsNote/" + selectedYear() + "에 " + finalSaved + "장 저장됨", Toast.LENGTH_LONG).show();
        if (savedTabContent.getVisibility() == View.VISIBLE) loadSavedPhotos();
      });
    });
  }

  private void writeBitmapToGallery(Bitmap bitmap, String fileName, String year) throws IOException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
      values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KidsNote/" + year);
      Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      if (uri == null) throw new IOException("사진 저장 위치를 만들 수 없습니다.");
      try (OutputStream out = getContentResolver().openOutputStream(uri)) {
        if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("사진 저장 실패");
      }
    } else {
      File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KidsNote/" + year);
      if (!directory.exists() && !directory.mkdirs()) throw new IOException("사진 폴더를 만들 수 없습니다.");
      File target = new File(directory, fileName);
      try (OutputStream out = new FileOutputStream(target)) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("사진 저장 실패");
      }
      MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
    }
  }

  private void saveSinglePhoto(Photo photo, Button button) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      pendingSingleSave = photo;
      pendingSingleSaveButton = button;
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
      return;
    }
    button.setEnabled(false);
    button.setText("원본 저장 중…");
    networkIo.execute(() -> {
      Bitmap bitmap = null;
      boolean success = false;
      try {
        bitmap = loadOriginal(photo.url, 0);
        String year = yearOf(photo.date).isEmpty() ? selectedYear() : yearOf(photo.date);
        String dateToken = photo.date.length() >= 10 ? photo.date.substring(0, 10).replaceAll("\\D", "") : year;
        String fileName = "kidsnote_" + dateToken + "_" + Integer.toHexString(photo.url.hashCode()) + ".jpg";
        writeBitmapToGallery(bitmap, fileName, year);
        success = true;
      } catch (Exception ignored) {
      } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
      boolean saved = success;
      runOnUiThread(() -> {
        button.setEnabled(true);
        button.setText(saved ? "저장 완료 ✓" : "다시 저장");
        Toast.makeText(this, saved ? "사진 한 장을 갤러리에 저장했습니다." : "사진 저장에 실패했습니다.", Toast.LENGTH_LONG).show();
        if (saved && savedTabContent.getVisibility() == View.VISIBLE) loadSavedPhotos();
      });
    });
  }

  private void confirmBackup() {
    if (photos.isEmpty()) return;
    new AlertDialog.Builder(this)
        .setTitle(selectedYear() + "년 원본 사진을 저장할까요?")
        .setMessage("현재 화면의 이미지는 임시 썸네일입니다.\n\n" + photos.size() + "장의 원본을 이 기기의\nPictures/KidsNote/" + selectedYear() + " 폴더에 저장합니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton("원본 저장", (dialog, which) -> saveAll())
        .show();
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != STORAGE_PERMISSION_REQUEST) return;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      if (pendingSingleSave != null && pendingSingleSaveButton != null) {
        Photo photo = pendingSingleSave; Button button = pendingSingleSaveButton;
        pendingSingleSave = null; pendingSingleSaveButton = null;
        saveSinglePhoto(photo, button);
      } else saveAll();
    } else Toast.makeText(this, "사진을 저장하려면 저장소 권한이 필요합니다.", Toast.LENGTH_LONG).show();
  }

  private void setLoading(boolean loading, String message) {
    countText.setText(message);
    progress.setIndeterminate(loading);
    progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    findViewById(R.id.loadButton).setEnabled(!loading);
  }

  private void showConnected() {
    loginStatusBadge.setText("●  연결됨");
    loginStatusBadge.setTextColor(Color.rgb(18, 119, 88));
    loginStatusBadge.setBackgroundResource(R.drawable.bg_status_on);
    loginStatusDetail.setText((loginId.isEmpty() ? "ID 확인 안 됨" : "ID " + loginId) + " · 자녀 " + childId);
    ((Button) findViewById(R.id.loginButton)).setText("계정 변경");
  }

  private void showDisconnected() {
    loginStatusBadge.setText("●  로그인 필요");
    loginStatusBadge.setTextColor(Color.rgb(179, 68, 60));
    loginStatusBadge.setBackgroundResource(R.drawable.bg_status_off);
    loginStatusDetail.setText("계정을 연결해 주세요");
    ((Button) findViewById(R.id.loginButton)).setText("로그인");
  }

  private void showPhoto(int startIndex) {
    showPhoto(photos, startIndex, true);
  }

  private void showPhoto(ArrayList<Photo> sourcePhotos, int startIndex, boolean allowSave) {
    if (startIndex < 0 || startIndex >= sourcePhotos.size()) return;
    Photo initialPhoto = sourcePhotos.get(startIndex);
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.BLACK);
    ZoomImageView full = new ZoomImageView(this);
    frame.addView(full, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(18), dp(10), dp(8), dp(10));
    topBar.setBackgroundColor(Color.argb(190, 7, 17, 25));
    TextView dateText = new TextView(this);
    dateText.setText(displayDate(initialPhoto.date));
    dateText.setTextColor(Color.WHITE);
    dateText.setTextSize(18);
    dateText.setTypeface(null, android.graphics.Typeface.BOLD);
    topBar.addView(dateText, new LinearLayout.LayoutParams(0, dp(48), 1));
    Button close = new Button(this);
    close.setText("닫기");
    close.setTextColor(Color.WHITE);
    close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 38, 48)));
    topBar.addView(close, new LinearLayout.LayoutParams(dp(76), dp(48)));
    FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
    frame.addView(topBar, topParams);

    Button save = new Button(this);
    save.setText("이 사진 원본 저장");
    save.setTextColor(Color.rgb(7, 61, 49));
    save.setTextSize(16);
    save.setTypeface(null, android.graphics.Typeface.BOLD);
    save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 201, 151)));
    FrameLayout.LayoutParams saveParams = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
    saveParams.setMargins(dp(22), 0, dp(22), dp(22));
    frame.addView(save, saveParams);
    save.setVisibility(allowSave ? View.VISIBLE : View.GONE);

    ProgressBar loading = new ProgressBar(this);
    FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
    frame.addView(loading, loadingParams);
    Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    dialog.setContentView(frame);
    close.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
    applyInsets(frame);
    if (dialog.getWindow() != null) {
      dialog.getWindow().setStatusBarColor(Color.BLACK);
      dialog.getWindow().setNavigationBarColor(Color.BLACK);
      dialog.getWindow().getDecorView().setSystemUiVisibility(0);
    }
    int[] currentIndex = {startIndex};
    Bitmap[] displayedBitmap = {null};
    java.util.concurrent.atomic.AtomicInteger loadToken = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.IntConsumer displayPhoto = new java.util.function.IntConsumer() {
      @Override public void accept(int index) {
        if (index < 0 || index >= sourcePhotos.size() || index == currentIndex[0] && displayedBitmap[0] != null) return;
        int previousIndex = currentIndex[0];
        currentIndex[0] = index;
        Photo selected = sourcePhotos.get(index);
        int token = loadToken.incrementAndGet();
        dateText.setText(displayDate(selected.date));
        save.setEnabled(true);
        save.setText("이 사진 원본 저장");
        save.setOnClickListener(v -> saveSinglePhoto(selected, save));
        loading.setVisibility(View.VISIBLE);
        networkIo.execute(() -> {
          try {
            Bitmap bitmap = loadOriginal(selected.url, 2048);
            runOnUiThread(() -> {
              if (!dialog.isShowing() || token != loadToken.get()) {
                if (!bitmap.isRecycled()) bitmap.recycle();
                return;
              }
              Bitmap previous = displayedBitmap[0];
              displayedBitmap[0] = bitmap;
              full.setImageBitmap(bitmap);
              full.setTranslationX(index >= previousIndex ? dp(36) : -dp(36));
              full.setAlpha(.45f);
              full.animate().translationX(0).alpha(1f).setDuration(180).start();
              loading.setVisibility(View.GONE);
              if (previous != null && !previous.isRecycled()) previous.recycle();
            });
          } catch (Exception error) {
            runOnUiThread(() -> {
              if (token != loadToken.get()) return;
              currentIndex[0] = previousIndex;
              Photo previous = sourcePhotos.get(previousIndex);
              dateText.setText(displayDate(previous.date));
              save.setOnClickListener(v -> saveSinglePhoto(previous, save));
              loading.setVisibility(View.GONE);
              Toast.makeText(MainActivity.this, "사진을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            });
          }
        });
      }
    };
    full.setOnPhotoSwipeListener(direction -> displayPhoto.accept(currentIndex[0] + direction));
    dialog.setOnDismissListener(ignored -> {
      loadToken.incrementAndGet();
      full.setImageDrawable(null);
      if (displayedBitmap[0] != null && !displayedBitmap[0].isRecycled()) displayedBitmap[0].recycle();
    });
    displayPhoto.accept(startIndex);
  }

  private static String displayDate(String value) {
    Matcher matcher = Pattern.compile("(20\\d{2})-(\\d{2})-(\\d{2})").matcher(value == null ? "" : value);
    if (!matcher.find()) return "날짜 정보 없음";
    return Integer.parseInt(matcher.group(1)) + "년 " + Integer.parseInt(matcher.group(2)) + "월 " + Integer.parseInt(matcher.group(3)) + "일";
  }

  private static String first(JSONObject object, String... keys) {
    for (String key : keys) { String value = object.optString(key, ""); if (!value.isEmpty()) return value; }
    return "";
  }
  private static String readText(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
    while ((read = input.read(buffer)) >= 0) out.write(buffer, 0, read);
    return out.toString("UTF-8");
  }

  private class PhotoAdapter extends BaseAdapter {
    private final ArrayList<Photo> items;
    private final GridView target;
    PhotoAdapter(ArrayList<Photo> items, GridView target) { this.items = items; this.target = target; }
    public int getCount() { return items.size(); }
    public Object getItem(int p) { return items.get(p); }
    public long getItemId(int p) { return p; }
    public View getView(int position, View convert, android.view.ViewGroup parent) {
      ImageView image = convert instanceof ImageView ? (ImageView) convert : new ImageView(MainActivity.this);
      image.setLayoutParams(new AbsListView.LayoutParams(-1, galleryCellSize(target)));
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.animate().cancel();
      image.setAlpha(1f);
      image.setBackgroundColor(Color.rgb(226, 234, 232));
      image.setImageDrawable(null);
      String url = items.get(position).url;
      Bitmap cached = thumbnailCache.get(url);
      if (cached != null) image.setImageBitmap(cached);
      else {
        int generation = loadGeneration;
        thumbnailIo.execute(() -> {
          try {
            Bitmap bitmap = loadThumbnail(url, generation);
            if (bitmap != null) runOnUiThread(() -> {
              if (generation == loadGeneration && url.equals(image.getTag())) {
                image.setAlpha(.35f);
                image.setImageBitmap(bitmap);
                image.animate().alpha(1f).setDuration(140).start();
              }
            });
          }
          catch (Exception ignored) {}
        });
      }
      image.setTag(url);
      return image;
    }
  }

  private class ScheduleAdapter extends BaseAdapter {
    public int getCount() { return schedules.size(); }
    public Object getItem(int position) { return schedules.get(position); }
    public long getItemId(int position) { return position; }
    public View getView(int position, View convert, android.view.ViewGroup parent) {
      LinearLayout card = convert instanceof LinearLayout ? (LinearLayout) convert : new LinearLayout(MainActivity.this);
      card.removeAllViews();
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(dp(14), dp(12), dp(14), dp(12));
      card.setBackgroundColor(Color.WHITE);

      ScheduleItem item = schedules.get(position);
      TextView date = new TextView(MainActivity.this);
      date.setText(displayDate(item.date) + (item.time.isEmpty() ? "" : "  " + item.time));
      date.setTextColor(getColor(R.color.mint_dark));
      date.setTextSize(12);
      date.setTypeface(null, Typeface.BOLD);
      card.addView(date);

      TextView title = new TextView(MainActivity.this);
      title.setText(item.title);
      title.setTextColor(getColor(R.color.text_primary));
      title.setTextSize(15);
      title.setTypeface(null, Typeface.BOLD);
      LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
      titleParams.topMargin = dp(5);
      card.addView(title, titleParams);

      TextView context = new TextView(MainActivity.this);
      context.setText(item.context);
      context.setTextColor(getColor(R.color.text_secondary));
      context.setTextSize(11);
      context.setMaxLines(3);
      context.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams contextParams = new LinearLayout.LayoutParams(-1, -2);
      contextParams.topMargin = dp(4);
      card.addView(context, contextParams);
      return card;
    }
  }

  private static class ZoomImageView extends ImageView {
    interface OnPhotoSwipeListener { void onSwipe(int direction); }
    private final Matrix zoomMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float zoom = 1f;
    private OnPhotoSwipeListener photoSwipeListener;

    ZoomImageView(Context context) {
      super(context);
      setScaleType(ScaleType.MATRIX);
      scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override public boolean onScale(ScaleGestureDetector detector) {
          float requested = detector.getScaleFactor();
          float next = Math.max(1f, Math.min(5f, zoom * requested));
          float factor = next / zoom;
          zoom = next;
          zoomMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
          fixBounds();
          setImageMatrix(zoomMatrix);
          return true;
        }
      });
      gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
        @Override public boolean onDown(MotionEvent event) { return true; }
        @Override public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
          if (zoom <= 1f) return false;
          zoomMatrix.postTranslate(-distanceX, -distanceY);
          fixBounds();
          setImageMatrix(zoomMatrix);
          return true;
        }
        @Override public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
          if (first == null || last == null || photoSwipeListener == null) return false;
          float distance = last.getX() - first.getX();
          float threshold = 56f * getResources().getDisplayMetrics().density;
          if (Math.abs(distance) < threshold || Math.abs(velocityX) < Math.abs(velocityY)) return false;
          if (zoom > 1f) {
            RectF bounds = mappedBounds();
            float tolerance = 3f * getResources().getDisplayMetrics().density;
            boolean atRequestedEdge = distance < 0
                ? bounds.right <= getWidth() + tolerance
                : bounds.left >= -tolerance;
            if (!atRequestedEdge) return false;
          }
          photoSwipeListener.onSwipe(distance < 0 ? 1 : -1);
          return true;
        }
        @Override public boolean onDoubleTap(MotionEvent event) {
          if (zoom > 1f) resetZoom();
          else {
            zoom = 2.5f;
            zoomMatrix.postScale(zoom, zoom, event.getX(), event.getY());
            fixBounds();
            setImageMatrix(zoomMatrix);
          }
          return true;
        }
      });
    }

    void setOnPhotoSwipeListener(OnPhotoSwipeListener listener) { photoSwipeListener = listener; }

    @Override public void setImageBitmap(Bitmap bitmap) {
      super.setImageBitmap(bitmap);
      post(this::resetZoom);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
      super.onSizeChanged(width, height, oldWidth, oldHeight);
      post(this::resetZoom);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      scaleDetector.onTouchEvent(event);
      gestureDetector.onTouchEvent(event);
      return true;
    }

    private void resetZoom() {
      android.graphics.drawable.Drawable drawable = getDrawable();
      if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
      float drawableWidth = drawable.getIntrinsicWidth();
      float drawableHeight = drawable.getIntrinsicHeight();
      if (drawableWidth <= 0 || drawableHeight <= 0) return;
      float scale = Math.min(getWidth() / drawableWidth, getHeight() / drawableHeight);
      float dx = (getWidth() - drawableWidth * scale) / 2f;
      float dy = (getHeight() - drawableHeight * scale) / 2f;
      zoomMatrix.reset();
      zoomMatrix.postScale(scale, scale);
      zoomMatrix.postTranslate(dx, dy);
      zoom = 1f;
      setImageMatrix(zoomMatrix);
    }

    private void fixBounds() {
      android.graphics.drawable.Drawable drawable = getDrawable();
      if (drawable == null) return;
      RectF bounds = mappedBounds();
      float dx = 0f, dy = 0f;
      if (bounds.width() <= getWidth()) dx = (getWidth() - bounds.width()) / 2f - bounds.left;
      else if (bounds.left > 0) dx = -bounds.left;
      else if (bounds.right < getWidth()) dx = getWidth() - bounds.right;
      if (bounds.height() <= getHeight()) dy = (getHeight() - bounds.height()) / 2f - bounds.top;
      else if (bounds.top > 0) dy = -bounds.top;
      else if (bounds.bottom < getHeight()) dy = getHeight() - bounds.bottom;
      zoomMatrix.postTranslate(dx, dy);
    }

    private RectF mappedBounds() {
      android.graphics.drawable.Drawable drawable = getDrawable();
      if (drawable == null) return new RectF();
      RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
      zoomMatrix.mapRect(bounds);
      return bounds;
    }
  }
  private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
  private static class Photo {
    final String url, date;
    Photo(String url, String date) { this.url = url; this.date = date == null ? "" : date; }
  }
  private static class ScheduleItem {
    final String date, time, title, context, written;
    ScheduleItem(String date, String time, String title, String context, String written) {
      this.date = date;
      this.time = time;
      this.title = title;
      this.context = context;
      this.written = written;
    }
  }

  @Override protected void onDestroy() {
    mainHandler.removeCallbacks(sessionTimeout);
    webView.destroy();
    networkIo.shutdownNow();
    thumbnailIo.shutdownNow();
    super.onDestroy();
  }
}
