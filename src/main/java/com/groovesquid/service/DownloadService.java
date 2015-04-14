package com.groovesquid.service;

import com.google.gson.Gson;
import com.groovesquid.Groovesquid;
import com.groovesquid.model.*;
import com.groovesquid.util.FilenameSchemeParser;
import com.groovesquid.util.Utils;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.String.format;

@SuppressWarnings({"unchecked", "rawtypes", "serial"})
public class DownloadService {

    private final static Logger log = Logger.getLogger(Groovesquid.class.getName());

    private final ExecutorService executorService;
    private final ExecutorService executorServiceForPlay;
    private final List<DownloadTask> currentlyRunningDownloads = new ArrayList<DownloadTask>();
    private final FilenameSchemeParser filenameSchemeParser;

    private long nextSongMustSleepUntil;

    public DownloadService() {
        this.executorService = Executors.newFixedThreadPool(Groovesquid.getConfig().getMaxParallelDownloads());
        this.executorServiceForPlay = Executors.newFixedThreadPool(1);
        this.filenameSchemeParser = new FilenameSchemeParser();
    }

    public FilenameSchemeParser getFilenameSchemeParser() {
        return filenameSchemeParser;
    }

    public synchronized Track download(Song song) {
        return download(song, null);
    }

    public synchronized Track download(Song song, DownloadListener downloadListener) {
        File downloadDir = new File(Groovesquid.getConfig().getDownloadDirectory());
        String fileName = filenameSchemeParser.parse(song, Groovesquid.getConfig().getFileNameScheme());
        Store store = new FileStore(fileName, downloadDir);
        return download(song, store, downloadListener, false);
    }

    public synchronized Track downloadToMemory(Song song) {
        return downloadToMemory(song, null);
    }

    public synchronized Track downloadToMemory(Song song, DownloadListener downloadListener) {
        Store store = new MemoryStore(song.toString());
        return download(song, store, downloadListener, true);
    }

    private Track download(Song song, Store store, DownloadListener downloadListener, boolean forPlay) {
        song.setDownloaded(true);
        Track track = new Track(song, store);
        int additionalAbortDelay = 0;
        boolean downloadWasInterrupted = cancelDownload(track, true);
        if (downloadWasInterrupted && !forPlay)
            additionalAbortDelay += 5000;
        additionalAbortDelay += Math.max(nextSongMustSleepUntil - System.currentTimeMillis(), 0);
        DownloadTask downloadTask = new DownloadTask(track, additionalAbortDelay, downloadListener);
        currentlyRunningDownloads.add(downloadTask);
        if (forPlay) {
            executorServiceForPlay.submit(downloadTask);
        } else {
            executorService.submit(downloadTask);
        }
        nextSongMustSleepUntil = Math.max(System.currentTimeMillis(), nextSongMustSleepUntil + 1000);
        return track;
    }
    
    public synchronized boolean cancelDownload(Track track, boolean deleteStore) {
        return cancelDownload(track, deleteStore, false);
    }

    public synchronized boolean cancelDownload(Track track, boolean deleteStore, boolean safeDelete) {
        DownloadTask downloadTask = findDownloadTask(track);
        if(safeDelete) {
            if(downloadTask == null) {
                downloadTask = new DownloadTask(track, 0, null);
                currentlyRunningDownloads.add(downloadTask);
            }
        }
        return cancelDownload(downloadTask, deleteStore);
    }

    private synchronized boolean cancelDownload(DownloadTask downloadTask, boolean deleteStore) {
        boolean downloadWasInterrupted = false;
        if (downloadTask != null) {
            currentlyRunningDownloads.remove(downloadTask);
            downloadWasInterrupted = downloadTask.abort();
            if (deleteStore) {
                if(downloadTask.track.getStore() == null) {
                    File downloadDir = new File(Groovesquid.getConfig().getDownloadDirectory());
                    Store store = new FileStore(downloadTask.track.getPath(), downloadDir);
                    downloadTask.track.setStore(store);
                }
                downloadTask.track.getStore().deleteStore();
            }
            downloadTask.track.setStatus(Track.Status.CANCELLED);
            downloadTask.fireDownloadStatusChanged();
        }
        return downloadWasInterrupted;
    }

    private DownloadTask findDownloadTask(Track track) {
        for (DownloadTask downloadTask : currentlyRunningDownloads) {
            if (downloadTask.track.getStore().isSameLocation(track.getStore())) {
                return downloadTask;
            }
        }
        return null;
    }

    public void shutdown() {
        executorService.shutdownNow();
        executorServiceForPlay.shutdownNow();
        ArrayList<DownloadTask> downloadsCopy = new ArrayList<DownloadTask>(currentlyRunningDownloads);
        for (DownloadTask downloadTask : downloadsCopy) {
            cancelDownload(downloadTask, true);
        }
    }
    
    public boolean areCurrentlyRunningDownloads() {
        return currentlyRunningDownloads.size() > 0;
    }


    private class DownloadTask implements Runnable {
        private final Track track;
        private final int initialDelay;
        private final DownloadListener downloadListener;
        private volatile HttpPost httpPost;
        private volatile boolean aborted;

        public DownloadTask(Track track, int initialDelay, DownloadListener downloadListener) {
            this.track = track;
            this.initialDelay = initialDelay;
            this.downloadListener = downloadListener;
        }
        
        class Response {
            private HashMap<String, Object> header;
            private HashMap<String, Object> result;
            private HashMap<String, Object> fault;

            public Response(HashMap<String, Object> header, HashMap<String, Object> result) {
                this.header = header;
                this.result = result;
            }

            public HashMap getHeader() {
                return this.header;
            }

            public HashMap<String, Object> getResult() {
                return this.result;
            }

            public HashMap<String, Object> getFault() {
                return this.fault;
            }
        }

        public void run() {
            try {
                if (track.getStatus() == Track.Status.CANCELLED)
                    return;
                Thread.sleep(initialDelay);
                if (track.getStatus() == Track.Status.CANCELLED)
                    return;
                
                track.setStatus(Track.Status.INITIALIZING);
                fireDownloadStatusChanged();
                
                Gson gson = new Gson();

                Response response = gson.fromJson(Groovesquid.getGroovesharkClient().sendRequest("getStreamKeyFromSongIDEx", new HashMap() {{
                    put("country", Groovesquid.getGroovesharkClient().getCountry());
                    put("mobile", "false");
                    put("prefetch", "false");
                    put("songID", track.getSong().getId());
                    put("type", "0");
                }}), Response.class);

                if(response.getFault() != null && response.getFault().get("code") == "256") {
                    log.info("INVALID TOKEN, BITCH");
                }

                HashMap<String, Object> result = response.getResult();

                track.setDownloadUrl("http://" + result.get("ip").toString() + "/stream.php");
                track.setStreamKey(result.get("streamKey").toString());
                track.setStreamServerID(result.get("streamServerID").toString());
                long uSecs = Long.valueOf(result.get("uSecs").toString());

                track.setStatus(Track.Status.DOWNLOADING);
                track.setStartDownloadTime(System.currentTimeMillis());
                if ((track.getSong().getDuration() == null || track.getSong().getDuration() <= 0.0) && uSecs > 0) {
                    track.getSong().setDuration(uSecs / 1000000.0);
                }
                fireDownloadStatusChanged();

                download(track);
                track.setStatus(Track.Status.FINISHED);
                fireDownloadStatusChanged();
                log.info("finished download track " + track);

                HashMap<String, Object> result2 = gson.fromJson(Groovesquid.getGroovesharkClient().sendRequest("markSongDownloadedEx", new HashMap() {{
                    put("songID", track.getSong().getId());
                    put("streamKey", track.getStreamKey());
                    put("streamServerID", track.getStreamServerID());
                }}), Response.class).getResult();
                if(!result2.get("Return").toString().equalsIgnoreCase("true")) {
                    log.severe("markSongDownloadedEx did not return true");
                }

            } catch (Exception ex) {
                if (aborted || ex instanceof InterruptedException) {
                    log.info("cancel download by request: " + track);
                    track.setStatus(Track.Status.CANCELLED);
                } else {
                    log.log(Level.SEVERE, "error download track " + track, ex);
                    track.setStatus(Track.Status.ERROR);
                    //track.setFault(ex);
                }
                track.getStore().deleteStore();
                fireDownloadStatusChanged();
            } finally {
                track.setStopDownloadTime(System.currentTimeMillis());
                synchronized (DownloadService.this) {
                    currentlyRunningDownloads.remove(this);
                }
                synchronized (this) {
                    httpPost = null;
                }
                fireDownloadStatusChanged();
            }
        }

        public synchronized boolean abort() {
            if (httpPost != null) {
                aborted = true;
                httpPost.abort();
                return true;
            }
            return false;
        }

        private void download(Track track) throws IOException {
            httpPost = new HttpPost(track.getDownloadUrl());
            httpPost.setHeader(HTTP.CONTENT_TYPE, "application/x-www-form-urlencoded");
            httpPost.setHeader(HTTP.CONN_KEEP_ALIVE, "300");
            httpPost.setEntity(new StringEntity("streamKey=" + track.getStreamKey()));
            HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();
            if (Groovesquid.getConfig().getProxyHost() != null && Groovesquid.getConfig().getProxyPort() != null) {
                httpClientBuilder.setProxy(new HttpHost(Groovesquid.getConfig().getProxyHost(), Groovesquid.getConfig().getProxyPort()));
            }
            HttpClient httpClient = httpClientBuilder.build();
            HttpResponse httpResponse = httpClient.execute(httpPost);
            HttpEntity httpEntity = httpResponse.getEntity();
            OutputStream outputStream = null;
            try {
                StatusLine statusLine = httpResponse.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                if (statusCode == HttpStatus.SC_OK) {
                    track.setTotalBytes(httpEntity.getContentLength());
                    Store store = track.getStore();
                    OutputStream storeOutputStream = store.getOutputStream();
                    outputStream = new MonitoredOutputStream(storeOutputStream);
                    InputStream instream = httpEntity.getContent();
                    byte[] buf = new byte[10240];
                    int l;
                    while ((l = instream.read(buf)) != -1) {
                        outputStream.write(buf, 0, l);
                    }
                    // need to close immediately otherwise we cannot write ID tags
                    outputStream.close();
                    outputStream = null;
                    // write ID tags
                    store.writeTrackInfo(track);
                } else {
                    throw new HttpResponseException(statusCode, format("%s: %d %s", track.getDownloadUrl(), statusCode, statusLine.getReasonPhrase()));
                }
            } finally {
                close(httpEntity);
                Utils.closeQuietly(outputStream, track.getStore().getDescription());
            }
        }

        private void fireDownloadStatusChanged() {
            if (downloadListener != null)
                downloadListener.statusChanged(track);
        }

        private void fireDownloadBytesChanged() {
            if (downloadListener != null)
                downloadListener.downloadedBytesChanged(track);
        }

        private void close(HttpEntity httpEntity) {
            try {
                EntityUtils.consume(httpEntity);
            } catch (IOException ignore) {
                // ignored
            }
            /*try {
                ((BasicManagedEntity) httpEntity).abortConnection();
            } catch (IOException ignore) {
                // ignored
            }*/
        }


        private class MonitoredOutputStream extends OutputStream {
            private final OutputStream outputStream;

            public MonitoredOutputStream(OutputStream outputStream) {
                this.outputStream = outputStream;
            }

            @Override
            public void close() throws IOException {
                outputStream.close();
            }

            @Override
            public void flush() throws IOException {
                outputStream.flush();
            }

            @Override
            public void write(byte[] b) throws IOException {
                outputStream.write(b);
                track.incDownloadedBytes(b.length);
                fireDownloadBytesChanged();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                outputStream.write(b, off, len);
                track.incDownloadedBytes(len);
                fireDownloadBytesChanged();
            }

            @Override
            public void write(int b) throws IOException {
                outputStream.write(b);
                track.incDownloadedBytes(1);
                fireDownloadBytesChanged();
            }
        }
    }
}
