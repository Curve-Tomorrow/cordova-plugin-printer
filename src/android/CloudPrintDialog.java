/*
    Copyright 2013-2014 appPlant UG

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */

package de.appplant.cordova.plugin.printer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.FileOutputStream;

/**
 * This plug in brings up a native overlay to print HTML documents using
 * AirPrint for iOS and Google Cloud Print for Android.
 */
public class Printer extends CordovaPlugin {

    private CallbackContext command;

    private static final String DEFAULT_DOC_NAME = "unknown";

    /**
     * Executes the request.
     *
     * This method is called from the WebView thread.
     * To do a non-trivial amount of work, use:
     *     cordova.getThreadPool().execute(runnable);
     *
     * To run on the UI thread, use:
     *     cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments in JSON form.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return                Whether the action was valid.
     */
    @Override
    public boolean execute (String action, JSONArray args,
            CallbackContext callbackContext) throws JSONException {

        command = callbackContext;

        if (action.equalsIgnoreCase("isAvailable")) {
            isAvailable();

            return true;
        }

        if (action.equalsIgnoreCase("print")) {
            print(args);

            return true;
        }

        // Returning false results in a "MethodNotFound" error.
        return false;
    }

    /**
     * Informs if the device is able to print documents.
     * A Internet connection is required to load the cloud print dialog.
     */
    private void isAvailable () {
        Boolean supported   = isOnline();
        PluginResult result = new PluginResult(PluginResult.Status.OK, supported);

        command.sendPluginResult(result);
    }

    /**
     * Create an intent with the content to print out
     * and sends that to the cloud print activity.
     *
     * @param args
     *      The exec arguments as JSON
     */
    private void print (JSONArray args) {
        final String content = args.optString(0, "<html></html>");
        final String title = args.optJSONObject(1)
                                   .optString("name", DEFAULT_DOC_NAME);

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                WebView browser = new WebView(cordova.getActivity());

                new ContentClient(content, browser) {
                    @Override
                    void onContentReady(Uri contentFile) {
                        if (hasGoogleCloudPrintApp()) {
                            printViaGoogleCloudPrintApp(
                                    contentFile, title);
                        } else {
                            printViaGoogleCloudPrintDialog(
                                    contentFile, title);
                        }
                    }
                };
            }
        });
    }

    /**
     * Checks if the device is connected
     * to the Internet.
     *
     * @return
     *      true if online otherwise false
     */
    private Boolean isOnline () {
        Activity activity = cordova.getActivity();
        ConnectivityManager conMGr =
                (ConnectivityManager) activity.getSystemService(
                        Context.CONNECTIVITY_SERVICE);

        NetworkInfo netInfo = conMGr.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnected();
    }

    /**
     * Ask the package manager if the google cloud print app
     * is installed on the device.
     *
     * @return
     *      true if yes otherwise false
     */
    private boolean hasGoogleCloudPrintApp() {
        PackageManager pm = cordova.getActivity().getPackageManager();

        try {
            pm.getPackageInfo("com.google.android.apps.cloudprint", 0);
            return true;
        } catch(PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Uses the native cloud print app to print the content.
     *
     * @param contentFile
     *      The URI pointing to the content
     * @param title
     *      The title for the print job
     */
    private void printViaGoogleCloudPrintApp(Uri contentFile, String title) {
        Intent intent = new Intent(Intent.ACTION_SEND);

        intent.setPackage("com.google.android.apps.cloudprint");
        intent.setDataAndType(contentFile, "image/*");
        intent.putExtra(Intent.EXTRA_TITLE, title);

        cordova.startActivityForResult(this, intent, 0);
    }

    /**
     * Uses the cloud print web dialog to print the content.
     *
     * @param contentFile
     *      The URI pointing to the content
     * @param title
     *      The title for the print job
     */
    private void printViaGoogleCloudPrintDialog(Uri contentFile, String title) {
        Intent intent = new Intent(
                cordova.getActivity(), CloudPrintDialog.class);

        intent.setDataAndType(contentFile, "text/html");
        intent.putExtra(Intent.EXTRA_TITLE, title);

        cordova.startActivityForResult(this, intent, 0);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        command.success();
        command = null;
    }

    /**
     * Holds HTML content passed from WebView.
     */
    private class ContentHolder {

        String htmlContent;

        /**
         * @return
         *      If the content is available or not.
         */
        public boolean isContentReady() {
            return htmlContent != null;
        }

        /**
         * Sets HTML content to hold.
         */
        @JavascriptInterface
        @SuppressWarnings("UnusedDeclaration")
        public void setContent(String htmlContent) {
            this.htmlContent = htmlContent;
        }

        /**
         * @return
         *      URI of temporary file which
         *      contains HTML content.
         */
        public Uri getContentAsFile() {
            String tmpFileName = "print_page_tmp.html";

            try {
                // Create a file to save the give string
                FileOutputStream fos = cordova.getActivity().openFileOutput(
                        tmpFileName, Activity.MODE_PRIVATE);

                // Write string into the file and flush the output stream
                fos.write(htmlContent.getBytes());
                fos.flush();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }

            // Get URI of the created file.
            return Uri.fromFile(
                    cordova.getActivity().getFileStreamPath(tmpFileName));
        }
    }

    /**
     * Custom web browser client to easily get
     * the HTML content as an URI.
     */
    abstract class ContentClient extends WebViewClient {

        private WebView browser;

        private final ContentHolder contentHolder =
                new ContentHolder();

        ContentClient(String content, WebView webView) {
            this.browser = webView;

            initWebView();
            loadContent(content);
        }

        /**
         * Configures the WebView components which
         * will hold the print content.
         */
        @SuppressLint("AddJavascriptInterface")
        private void initWebView () {
            WebSettings settings = browser.getSettings();

            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setJavaScriptEnabled(true);

            browser.addJavascriptInterface(
                    contentHolder, "ContentHolder");

            browser.setWebViewClient(this);
        }

        /**
         * Loads the content into the web view.
         *
         * @param content
         *      Either an HTML string or URI
         */
        private void loadContent(String content) {
            if (content.startsWith("http") || content.startsWith("file:")) {
                browser.loadUrl(content);
            } else {
                //Set base URI to the assets/www folder
                String baseURL = webView.getUrl();
                baseURL        = baseURL.substring(0, baseURL.lastIndexOf('/') + 1);

                browser.loadDataWithBaseURL(
                        baseURL, content, "text/html", "UTF-8", null);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.loadUrl("javascript:window.ContentHolder.setContent(" +
                    "new XMLSerializer().serializeToString(document));");

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    Looper.prepare();

                    for (;;)
                        if (contentHolder.isContentReady()) {
                            onContentReady(contentHolder.getContentAsFile());
                            break;
                        }
                }
            });
        }

        /**
         * Called after onPageFinished when the content
         * has been set through the client.
         *
         * @param contentFile
         *      URI of temporary file which
         *      contains HTML content
         */
        abstract void onContentReady(Uri contentFile);
    }
}