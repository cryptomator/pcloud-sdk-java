/*
 * Copyright (c) 2017 pCloud AG
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
 *
 * ** Notice of Modification **
 *
 * This file has been altered from its original version by the Cryptomator team.
 * For a detailed history of modifications, please refer to the version control log.
 *
 * The original file can be found at https://github.com/pCloud/pcloud-sdk-java
 *
 * --
 *
 * https://cryptomator.org/
 */

package com.pcloud.sdk.internal;

import static com.pcloud.sdk.internal.FileIdUtils.isFile;
import static com.pcloud.sdk.internal.FileIdUtils.toFileId;
import static com.pcloud.sdk.internal.FileIdUtils.toFolderId;
import static com.pcloud.sdk.internal.IOUtils.closeQuietly;
import static com.pcloud.sdk.internal.RealFileLink.requireLinkNotNull;
import static com.pcloud.sdk.internal.RealFileLink.requireUrlFromLink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.pcloud.sdk.ApiClient;
import com.pcloud.sdk.ApiError;
import com.pcloud.sdk.Authenticator;
import com.pcloud.sdk.Call;
import com.pcloud.sdk.Checksums;
import com.pcloud.sdk.DataSink;
import com.pcloud.sdk.DataSource;
import com.pcloud.sdk.DownloadOptions;
import com.pcloud.sdk.FileLink;
import com.pcloud.sdk.ProgressListener;
import com.pcloud.sdk.RemoteEntry;
import com.pcloud.sdk.RemoteFile;
import com.pcloud.sdk.RemoteFolder;
import com.pcloud.sdk.UploadOptions;
import com.pcloud.sdk.UserInfo;
import com.pcloud.sdk.internal.networking.APIHttpException;
import com.pcloud.sdk.internal.networking.ApiResponse;
import com.pcloud.sdk.internal.networking.ChecksumsResponse;
import com.pcloud.sdk.internal.networking.GetFileResponse;
import com.pcloud.sdk.internal.networking.GetFolderResponse;
import com.pcloud.sdk.internal.networking.GetLinkResponse;
import com.pcloud.sdk.internal.networking.UploadFilesResponse;
import com.pcloud.sdk.internal.networking.UserInfoResponse;
import com.pcloud.sdk.internal.networking.serialization.ByteStringTypeAdapter;
import com.pcloud.sdk.internal.networking.serialization.DateTypeAdapter;
import com.pcloud.sdk.internal.networking.serialization.UnmodifiableListTypeFactory;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

class RealApiClient implements ApiClient {

    private static final String MULTIPART_BOUNDARY = "----pCloud-SDK-" + Version.NAME + "-" + UUID.randomUUID() + "----";

    private final long progressCallbackThresholdBytes;
    private final Authenticator authenticator;
    private final Gson gson;
    private final OkHttpClient httpClient;
    private final Executor callbackExecutor;
    private final HttpUrl apiHost;
	private List<Interceptor> interceptors;

    RealApiClient() {
        this(new RealApiServiceBuilder());
    }

    RealApiClient(RealApiServiceBuilder builder) {
        Map<String, String> globalParams = new TreeMap<>();
        globalParams.put("timeformat", "timestamp");
        String userAgent = String.format(Locale.US, "pCloud SDK Java %s", Version.NAME);
        OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder()
                .readTimeout(builder.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(builder.writeTimeoutMs(), TimeUnit.MILLISECONDS)
                .connectTimeout(builder.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .addInterceptor(new GlobalRequestInterceptor(userAgent, globalParams));

        if (builder.dispatcher() != null) {
            httpClientBuilder.dispatcher(builder.dispatcher());
        }

        if (builder.connectionPool() != null) {
            httpClientBuilder.connectionPool(builder.connectionPool());
        }

        if (builder.cache() != null) {
            httpClientBuilder.cache(builder.cache());
        }

        httpClientBuilder.authenticator(okhttp3.Authenticator.NONE);
        this.authenticator = builder.authenticator();
        if (authenticator != null) {
            httpClientBuilder.addInterceptor((RealAuthenticator) builder.authenticator());
        }

        this.interceptors = builder.interceptors();
		if (interceptors != null) {
			for (Interceptor interceptor : interceptors) {
				httpClientBuilder.addInterceptor(interceptor);
			}
		}

        this.httpClient = httpClientBuilder.build();
        this.callbackExecutor = builder.callbackExecutor();
        this.progressCallbackThresholdBytes = builder.progressCallbackThresholdBytes();
        this.gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .registerTypeAdapterFactory(new RealRemoteEntry.TypeAdapterFactory())
                .registerTypeAdapterFactory(new UnmodifiableListTypeFactory())
                .registerTypeAdapter(RemoteEntry.class, new RealRemoteEntry.FileEntryDeserializer())
                .registerTypeAdapter(Date.class, new DateTypeAdapter())
                .registerTypeAdapter(RealRemoteFile.class, new RealRemoteFile.InstanceCreator(this))
                .registerTypeAdapter(RealRemoteFolder.class, new RealRemoteFolder.InstanceCreator(this))
                .registerTypeAdapter(ByteString.class, new ByteStringTypeAdapter())
                .create();
        this.apiHost = builder.apiHost();
    }

    @Override
    public Call<RemoteFolder> listFolder(long folderId) {
        return listFolder(folderId, false);
    }

    @Override
    public Call<RemoteFolder> listFolder(long folderId, boolean recursively) {
        HttpUrl.Builder urlBuilder = apiHost.newBuilder()
                .addPathSegment("listfolder")
                .addQueryParameter("folderid", String.valueOf(folderId));
        if (recursively) {
            urlBuilder.addEncodedQueryParameter("recursive", String.valueOf(1));
        }

        Request request = newRequest()
                .url(urlBuilder.build())
                .get()
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFolder> listFolder(String path) {
        return listFolder(path, false);
    }

    @Override
    public Call<RemoteFolder> listFolder(String path, boolean recursively) {
        requireValidPath(path);
        HttpUrl.Builder urlBuilder = apiHost.newBuilder()
                .addPathSegment("listfolder")
                .addEncodedQueryParameter("path", path);
        if (recursively) {
            urlBuilder.addEncodedQueryParameter("recursive", String.valueOf(1));
        }

        Request request = newRequest()
                .url(urlBuilder.build())
                .get()
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFile> createFile(RemoteFolder folder, String filename, DataSource data) {
        return createFile(folder, filename, data, null, null, UploadOptions.DEFAULT);
    }

    @Override
    public Call<RemoteFile> createFile(RemoteFolder folder, String filename, DataSource data, UploadOptions uploadOptions) {
        return createFile(folder, filename, data, null, null, uploadOptions);
    }

    @Override
    public Call<RemoteFile> createFile(RemoteFolder folder, String filename, DataSource data, Date modifiedDate, ProgressListener listener) {
        return createFile(folder, filename, data, modifiedDate, listener, UploadOptions.DEFAULT);
    }

    @Override
    public Call<RemoteFile> createFile(RemoteFolder folder, String filename, DataSource data, Date modifiedDate, ProgressListener listener, UploadOptions uploadOptions) {
        if (folder == null) {
            throw new IllegalArgumentException("Folder argument cannot be null.");
        }
        return createFile(folder.folderId(), filename, data, modifiedDate, listener, uploadOptions);
    }

    @Override
    public Call<RemoteFile> createFile(long folderId, String filename, DataSource data) {
        return createFile(folderId, filename, data, null, null, UploadOptions.DEFAULT);
    }

    @Override
    public Call<RemoteFile> createFile(long folderId, String filename, DataSource data, UploadOptions uploadOptions) {
        return createFile(folderId, filename, data, null, null, uploadOptions);
    }

    @Override
    public Call<RemoteFile> createFile(long folderId, String filename, DataSource data, Date modifiedDate, ProgressListener listener) {
        return createFile(folderId, filename, data, modifiedDate, listener, UploadOptions.DEFAULT);
    }

    @Override
    public Call<RemoteFile> createFile(long folderId, String filename, final DataSource data, Date modifiedDate, final ProgressListener listener, final UploadOptions uploadOptions) {
        return createFile(folderId, null, filename, data, modifiedDate, listener, uploadOptions);
    }

    @Override
    public Call<RemoteFile> createFile(String path, String filename, DataSource data) {
        return createFile(path, filename, data, null, null, UploadOptions.DEFAULT);
    }

    @Override
    public Call<RemoteFile> createFile(String path, String filename, DataSource data, UploadOptions uploadOptions) {
        return createFile(path, filename, data, null, null, uploadOptions);
    }

    @Override
    public Call<RemoteFile> createFile(String path, String filename, DataSource data, Date modifiedDate, ProgressListener listener) {
        return createFile(path, filename, data, modifiedDate, listener, UploadOptions.DEFAULT);
    }

    @Override
    public Call<RemoteFile> createFile(String path, String filename, final DataSource data, Date modifiedDate, final ProgressListener listener, final UploadOptions uploadOptions) {
        requireValidPath(path);
        return createFile(null, path, filename, data, modifiedDate, listener, uploadOptions);
    }

    private Call<RemoteFile> createFile(Long folderId, String path, String filename, final DataSource data, Date modifiedDate, final ProgressListener listener, final UploadOptions uploadOptions) {
        if (filename == null) {
            throw new IllegalArgumentException("Filename cannot be null.");
        }
        if (data == null) {
            throw new IllegalArgumentException("File data cannot be null.");
        }

        if (uploadOptions == null) {
            throw new IllegalArgumentException("Upload options cannot be null.");
        }

        RequestBody dataBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("multipart/form-data");
            }

            @Override
            public void writeTo(@NotNull BufferedSink sink) throws IOException {
                if (listener != null) {
                    ProgressListener realListener = listener;
                    if (callbackExecutor != null) {
                        realListener = new ExecutorProgressListener(listener, callbackExecutor);
                    }

                    BufferedSink targetSink = Okio.buffer(new ProgressCountingSink(
                            sink,
                            data.contentLength(),
                            realListener,
                            progressCallbackThresholdBytes));
                    data.writeTo(targetSink);
                    targetSink.emit();
                } else {
                    data.writeTo(sink);
                }
            }

            @Override
            public long contentLength() {
                long contentLength = data.contentLength();
                if (contentLength < 0)
                    throw new IllegalArgumentException("Content length must be >= 0.");
                return contentLength;
            }
        };

        RequestBody compositeBody = new MultipartBody.Builder(MULTIPART_BOUNDARY)
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, dataBody)
                .build();

        HttpUrl.Builder urlBuilder = apiHost.newBuilder().
                addPathSegment("uploadfile")
                .addQueryParameter("renameifexists", String.valueOf(uploadOptions.overrideFile() ? 0 : 1))
                .addQueryParameter("nopartial", String.valueOf(uploadOptions.partialUpload() ? 0 : 1));

        if (folderId != null) {
            urlBuilder.addQueryParameter("folderid", String.valueOf(folderId));
        }

        if (path != null) {
            urlBuilder.addEncodedQueryParameter("path", path);
        }

        if (modifiedDate != null) {
            urlBuilder.addQueryParameter("mtime", String.valueOf(TimeUnit.MILLISECONDS.toSeconds(modifiedDate.getTime())));
        }

        Request uploadRequest = new Request.Builder()
                .url(urlBuilder.build())
                .method("POST", compositeBody)
                .build();

        return newCall(uploadRequest, response -> {
            UploadFilesResponse body = getAsApiResponse(response, UploadFilesResponse.class);
            if (!body.getUploadedFiles().isEmpty()) {
                return body.getUploadedFiles().get(0);
            } else {
                throw new IOException("API uploaded file but did not return remote file data.");
            }
        });
    }

    @Override
    public Call<Boolean> deleteFile(RemoteFile file) {
        if (file == null) {
            throw new IllegalArgumentException("File argument cannot be null.");
        }
        return deleteFile(file.fileId());
    }

    @Override
    public Call<Boolean> deleteFile(long fileId) {
        Request request = new Request.Builder()
                .url(apiHost.newBuilder()
                        .addPathSegment("deletefile")
                        .build())
                .get()
                .post(new FormBody.Builder()
                        .add("fileid", String.valueOf(fileId))
                        .build())
                .build();
        return newCall(request, response -> {
            GetFileResponse body = deserializeResponseBody(response, GetFileResponse.class);
            return body.isSuccessful() && body.getFile() != null;
        });
    }

    @Override
    public Call<Boolean> deleteFile(String path) {
        requireValidPath(path);
        Request request = new Request.Builder()
                .url(apiHost.newBuilder()
                        .addPathSegment("deletefile")
                        .build())
                .get()
                .post(new FormBody.Builder()
                        .addEncoded("path", path)
                        .build())
                .build();
        return newCall(request, response -> {
            GetFileResponse body = deserializeResponseBody(response, GetFileResponse.class);
            return body.isSuccessful() && body.getFile() != null;
        });
    }

    @Override
    public Call<FileLink> createFileLink(RemoteFile file, DownloadOptions options) {
        if (file == null) {
            throw new IllegalArgumentException("File argument cannot be null.");
        }
        return createFileLink(file.fileId(), options);
    }

    @Override
    public Call<FileLink> createFileLink(long fileId, DownloadOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("DownloadOptions parameter cannot be null.");
        }

        Request request = newDownloadLinkRequest(fileId, null, options);

        return newCall(request, this::getAsFileLink);

    }

    @Override
    public Call<FileLink> createFileLink(String path, DownloadOptions options) {
        requireValidPath(path);
        if (options == null) {
            throw new IllegalArgumentException("DownloadOptions parameter cannot be null.");
        }

        Request request = newDownloadLinkRequest(null, path, options);

        return newCall(request, this::getAsFileLink);
    }

    private FileLink getAsFileLink(Response response) throws IOException, ApiError {
        GetLinkResponse body = getAsApiResponse(response, GetLinkResponse.class);
        List<URL> downloadUrls = new ArrayList<>(body.getHosts().size());
        for (String host : body.getHosts()) {
            downloadUrls.add(new URL("https", host, body.getPath()));
        }

        return new RealFileLink(RealApiClient.this, body.getExpires(), downloadUrls);
    }

    private Request newDownloadLinkRequest(Long fileId, String path, DownloadOptions options) {
        HttpUrl.Builder urlBuilder = apiHost.newBuilder().
                addPathSegment("getfilelink");

        if (fileId != null) {
            urlBuilder.addQueryParameter("fileid", String.valueOf(fileId));
        }

        if (path != null) {
            urlBuilder.addEncodedQueryParameter("path", path);
        }

        if (options.forceDownload()) {
            urlBuilder.addQueryParameter("forcedownload", String.valueOf(1));
        }

        if (options.skipFilename()) {
            urlBuilder.addQueryParameter("skipfilename", String.valueOf(1));
        }

        if (options.contentType() != null) {
            MediaType mediaType = MediaType.parse(options.contentType());
            if (mediaType == null) {
                throw new IllegalArgumentException("Invalid or not well-formatted content type DownloadOptions argument");
            }
            urlBuilder.addQueryParameter("contenttype", mediaType.toString());
        }

        return new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build();
    }

    @Override
    public Call<Void> download(FileLink fileLink, DataSink sink) {
        requireLinkNotNull(fileLink);
        return download(fileLink, fileLink.bestUrl(), sink, null);
    }

    @Override
    public Call<Void> download(FileLink fileLink, DataSink sink, ProgressListener listener) {
        requireLinkNotNull(fileLink);
        return download(fileLink, fileLink.bestUrl(), sink, listener);
    }

    @Override
    public Call<Void> download(FileLink fileLink, URL linkVariant, final DataSink sink, final ProgressListener listener) {
        if (fileLink == null) {
            throw new IllegalArgumentException("FileLink argument cannot be null.");
        }

        requireUrlFromLink(fileLink, linkVariant);

        if (sink == null) {
            throw new IllegalArgumentException("DataSink argument cannot be null.");
        }

        Request request = newDownloadRequest(linkVariant);

        return newCall(request, response -> {
            try {
                BufferedSource source = getAsRawBytes(response);
                if (listener != null) {
                    ProgressListener realListener = listener;
                    if (callbackExecutor != null) {
                        realListener = new ExecutorProgressListener(listener, callbackExecutor);
                    }

                    source = Okio.buffer(new ProgressCountingSource(
                            source,
                            Objects.requireNonNull(response.body()).contentLength(),
                            realListener,
                            progressCallbackThresholdBytes));
                }

                sink.readAll(source);
                return null;
            } finally {
                closeQuietly(response);
            }
        });
    }

    @Override
    public Call<BufferedSource> download(RemoteFile file) {
        if (file == null) {
            throw new IllegalArgumentException("RemoteFile argument cannot be null.");
        }

        DownloadOptions options = DownloadOptions.create()
                .skipFilename(false)
                .contentType(file.contentType())
                .build();
        return newCall(newDownloadLinkRequest(file.fileId(), null, options), response -> {
            FileLink link = getAsFileLink(response);
            return newDownloadCall(link.bestUrl());
        });
    }

    @Override
    public Call<BufferedSource> download(FileLink fileLink) {
        requireLinkNotNull(fileLink);
        return download(fileLink, fileLink.bestUrl());
    }

    @Override
    public Call<BufferedSource> download(FileLink fileLink, URL linkVariant) {
        requireLinkNotNull(fileLink);
        requireUrlFromLink(fileLink, linkVariant);

        return newCall(newDownloadRequest(linkVariant), this::getAsRawBytes);
    }

    @Override
    public Call<RemoteFile> copyFile(long fileId, long toFolderId) {
        return copyFile(fileId, toFolderId, false);
    }

    @Override
    public Call<RemoteFile> copyFile(long fileId, long toFolderId, boolean overwrite) {
        FormBody.Builder builder = new FormBody.Builder()
                .add("fileid", String.valueOf(fileId))
                .add("tofolderid", String.valueOf(toFolderId));

        if (!overwrite) {
            builder.add("noover", String.valueOf(1));
        }

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("copyfile")
                        .build())
                .post(builder.build())
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFileResponse.class).getFile());
    }

    @Override
    public Call<RemoteFile> copyFile(RemoteFile file, RemoteFolder toFolder) {
        return copyFile(file, toFolder, false);
    }

    @Override
    public Call<RemoteFile> copyFile(RemoteFile file, RemoteFolder toFolder, boolean overwrite) {
        if (file == null) {
            throw new IllegalArgumentException("file argument cannot be null.");
        }
        if (toFolder == null) {
            throw new IllegalArgumentException("toFolder argument cannot be null.");
        }

        return copyFile(file.fileId(), toFolder.folderId(), overwrite);
    }

    @Override
    public Call<? extends RemoteEntry> copy(RemoteEntry file, RemoteFolder toFolder) {
        return copy(file, toFolder, false);
    }

    @Override
    public Call<? extends RemoteEntry> copy(RemoteEntry file, RemoteFolder toFolder, boolean overwriteFiles) {
        if (file == null) {
            throw new IllegalArgumentException("RemoteEntry argument cannot be null.");
        }
        if (toFolder == null) {
            throw new IllegalArgumentException("RemoteFolder argument cannot be null.");
        }
        final long toFolderId = toFolder.folderId();
        return file.isFolder() ?
                copyFolder(file.asFolder().folderId(), toFolderId, overwriteFiles) :
                copyFile(file.asFile().fileId(), toFolderId, overwriteFiles);
    }

    @Override
    public Call<? extends RemoteEntry> copy(String id, long toFolderId) {
        return copy(id, toFolderId, false);
    }

    @Override
    public Call<? extends RemoteEntry> copy(String id, long toFolderId, boolean overwriteFiles) {
        if (id == null) {
            throw new IllegalArgumentException("File identifier argument cannot be null.");
        }
        return isFile(id) ?
                copyFile(toFileId(id), toFolderId, overwriteFiles) :
                copyFile(toFolderId(id), toFolderId, overwriteFiles);
    }

    @Override
    public Call<? extends RemoteEntry> move(RemoteEntry file, RemoteFolder toFolder) {
        if (file == null) {
            throw new IllegalArgumentException("RemoteEntry argument cannot be null.");
        }
        if (toFolder == null) {
            throw new IllegalArgumentException("RemoteFolder argument cannot be null.");
        }

        final long toFolderId = toFolder.folderId();
        return file.isFolder() ?
                moveFolder(file.asFolder().folderId(), toFolderId) :
                moveFile(file.asFile().fileId(), toFolderId);
    }

    @Override
    public Call<? extends RemoteEntry> move(String id, long toFolderId) {
        if (id == null) {
            throw new IllegalArgumentException("File identifier argument cannot be null.");
        }
        return isFile(id) ?
                moveFile(toFileId(id), toFolderId) :
                moveFolder(toFolderId(id), toFolderId);
    }

    @Override
    public Call<Boolean> delete(RemoteEntry file) {
        if (file == null) {
            throw new IllegalArgumentException("RemoteEntry argument cannot be null.");
        }
        return file.isFolder() ?
                deleteFolder(file.asFolder().folderId()) :
                deleteFile(file.asFile().fileId());
    }

    @Override
    public Call<Boolean> delete(String id) {
        if (id == null) {
            throw new IllegalArgumentException("File identifier argument cannot be null.");
        }
        return isFile(id) ?
                deleteFile(toFileId(id)) :
                deleteFile(toFolderId(id));
    }

    @Override
    public Call<? extends RemoteEntry> rename(RemoteEntry file, String newFilename) {
        if (file == null) {
            throw new IllegalArgumentException("RemoteEntry argument cannot be null.");
        }
        if (newFilename == null) {
            throw new IllegalArgumentException("New filename argument cannot be null.");
        }

        return (file.isFolder() ?
                renameFolder(file.asFolder().folderId(), newFilename) :
                renameFile(file.asFile().fileId(), newFilename));
    }

    @Override
    public Call<? extends RemoteEntry> rename(String id, String newFilename) {
        if (id == null) {
            throw new IllegalArgumentException("File identifier argument cannot be null.");
        }
        return isFile(id) ?
                renameFile(toFileId(id), newFilename) :
                renameFolder(toFolderId(id), newFilename);
    }

    @Override
    public Call<RemoteFile> loadFile(long fileId) {
        HttpUrl.Builder urlBuilder = apiHost.newBuilder()
                .addPathSegment("stat")
                .addQueryParameter("fileid", String.valueOf(fileId));

        Request request = newRequest()
                .url(urlBuilder.build())
                .get()
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFileResponse.class).getFile());
    }

    @Override
    public Call<RemoteFile> loadFile(String path) {
        requireValidPath(path);
        HttpUrl.Builder urlBuilder = apiHost.newBuilder()
                .addPathSegment("stat")
                .addEncodedQueryParameter("path", String.valueOf(path));

        Request request = newRequest()
                .url(urlBuilder.build())
                .get()
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFileResponse.class).getFile());
    }

    @Override
    public Call<RemoteFolder> loadFolder(long folderId) {
        HttpUrl.Builder urlBuilder = apiHost.newBuilder()
                .addPathSegment("listfolder")
                .addQueryParameter("folderid", String.valueOf(folderId))
                .addQueryParameter("nofiles", String.valueOf(1));


        Request request = newRequest()
                .url(urlBuilder.build())
                .get()
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFolder> loadFolder(String path) {
        requireValidPath(path);
        HttpUrl.Builder urlBuilder = apiHost.newBuilder()
                .addPathSegment("listfolder")
                .addQueryParameter("path", path)
                .addQueryParameter("nofiles", String.valueOf(1));

        Request request = newRequest()
                .url(urlBuilder.build())
                .get()
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFile> moveFile(long fileId, long toFolderId) {
        RequestBody body = new FormBody.Builder()
                .add("fileid", String.valueOf(fileId))
                .add("tofolderid", String.valueOf(toFolderId))
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("renamefile")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFileResponse.class).getFile());
    }

    @Override
    public Call<RemoteFile> moveFile(String path, String toPath) {
        requireValidPath(path, "path");
        requireValidPath(toPath, "toPath");

        RequestBody body = new FormBody.Builder()
                .addEncoded("path", path)
                .addEncoded("topath", toPath)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("renamefile")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFileResponse.class).getFile());
    }

    @Override
    public Call<RemoteFile> moveFile(RemoteFile file, RemoteFolder toFolder) {
        if (file == null) {
            throw new IllegalArgumentException("file argument cannot be null.");
        }
        if (toFolder == null) {
            throw new IllegalArgumentException("toFolder argument cannot be null.");
        }

        return moveFile(file.fileId(), toFolder.folderId());
    }

    @Override
    public Call<RemoteFile> renameFile(long fileId, String newFilename) {
        if (newFilename == null) {
            throw new IllegalArgumentException("newFileName argument cannot be null.");
        }

        RequestBody body = new FormBody.Builder()
                .add("fileid", String.valueOf(fileId))
                .add("toname", newFilename)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("renamefile")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFileResponse.class).getFile());
    }

    @Override
    public Call<RemoteFile> renameFile(RemoteFile file, String newFilename) {
        if (file == null) {
            throw new IllegalArgumentException("file argument cannot be null.");
        }

        return renameFile(file.fileId(), newFilename);
    }

    @Override
    public Call<RemoteFolder> createFolder(RemoteFolder parentFolder, String folderName) {
        if (parentFolder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return createFolder(parentFolder.folderId(), folderName);
    }

    @Override
    public Call<RemoteFolder> createFolder(long parentFolderId, String folderName) {
        if (folderName == null) {
            throw new IllegalArgumentException("Folder name is null");
        }

        RequestBody body = new FormBody.Builder()
                .add("folderid", String.valueOf(parentFolderId))
                .add("name", folderName)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("createfolder").build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFolder> createFolder(String path) {
        requireValidPath(path);

        RequestBody body = new FormBody.Builder()
                .addEncoded("path", path)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("createfolder").build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<Boolean> deleteFolder(RemoteFolder folder) {
        if (folder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return deleteFolder(folder.folderId(), false);
    }

    @Override
    public Call<Boolean> deleteFolder(RemoteFolder folder, boolean recursively) {
        if (folder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return deleteFolder(folder.folderId(), recursively);
    }

    @Override
    public Call<Boolean> deleteFolder(long folderId) {
        return deleteFolder(folderId, false);
    }

    @Override
    public Call<Boolean> deleteFolder(long folderId, boolean recursively) {
        RequestBody body = new FormBody.Builder()
                .add("folderid", String.valueOf(folderId))
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment(recursively ? "deletefolderrecursive" : "deletefolder")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> {
            getAsApiResponse(response, ApiResponse.class);
            return true;
        });
    }

    @Override
    public Call<Boolean> deleteFolder(String path) {
        return deleteFolder(path, false);
    }

    @Override
    public Call<Boolean> deleteFolder(String path, boolean recursively) {
        requireValidPath(path);
        RequestBody body = new FormBody.Builder()
                .addEncoded("path", path)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment(recursively ? "deletefolderrecursive" : "deletefolder")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> {
            getAsApiResponse(response, ApiResponse.class);
            return true;
        });
    }

    @Override
    public Call<RemoteFolder> renameFolder(RemoteFolder folder, String newFolderName) {
        if (folder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return renameFolder(folder.folderId(), newFolderName);
    }

    @Override
    public Call<RemoteFolder> renameFolder(long folderId, String newFolderName) {
        if (newFolderName == null) {
            throw new IllegalArgumentException("Folder name is null");
        }

        RequestBody body = new FormBody.Builder()
                .add("folderid", String.valueOf(folderId))
                .add("toname", newFolderName)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("renamefolder")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFolder> moveFolder(RemoteFolder folder, RemoteFolder toFolder) {
        if (folder == null || toFolder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return moveFolder(folder.folderId(), toFolder.folderId());
    }

    @Override
    public Call<RemoteFolder> moveFolder(long folderId, long toFolderId) {
        RequestBody body = new FormBody.Builder()
                .add("folderid", String.valueOf(folderId))
                .add("tofolderid", String.valueOf(toFolderId))
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("renamefolder")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFolder> moveFolder(String path, String toPath) {
        requireValidPath(path, "path");
        requireValidPath(toPath, "toPath");

        RequestBody body = new FormBody.Builder()
                .addEncoded("path", path)
                .addEncoded("topath", toPath)
                .build();

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("renamefolder")
                        .build())
                .post(body)
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<RemoteFolder> copyFolder(RemoteFolder folder, RemoteFolder toFolder) {
        if (folder == null || toFolder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return copyFolder(folder.folderId(), toFolder.folderId(), false);
    }

    @Override
    public Call<RemoteFolder> copyFolder(RemoteFolder folder, RemoteFolder toFolder, boolean overwrite) {
        if (folder == null || toFolder == null) {
            throw new IllegalArgumentException("folder argument cannot be null.");
        }
        return copyFolder(folder.folderId(), toFolder.folderId(), overwrite);
    }

    @Override
    public Call<RemoteFolder> copyFolder(long folderId, long toFolderId) {
        return copyFolder(folderId, toFolderId, false);
    }

    @Override
    public Call<RemoteFolder> copyFolder(long folderId, long toFolderId, boolean overwrite) {
        FormBody.Builder builder = new FormBody.Builder()
                .add("folderid", String.valueOf(folderId))
                .add("tofolderid", String.valueOf(toFolderId));

        if (!overwrite) {
            builder.add("noover", String.valueOf(1));
            builder.add("skipexisting", String.valueOf(1));
        }

        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("copyfolder")
                        .build())
                .post(builder.build())
                .build();

        return newCall(request, response -> getAsApiResponse(response, GetFolderResponse.class).getFolder());
    }

    @Override
    public Call<UserInfo> getUserInfo() {
        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("userinfo")
                        .build())
                .get().build();

        return newCall(request, response -> {
            UserInfoResponse body = getAsApiResponse(response, UserInfoResponse.class);
            return new RealUserInfo(body.getUserId(),
                    body.getEmail(),
                    body.isEmailVerified(),
                    body.getTotalQuota(),
                    body.getUsedQuota());
        });
    }

    @Override
    public Call<Checksums> getChecksums(long fileId) {
        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("checksumfile")
                        .addQueryParameter("fileid", String.valueOf(fileId))
                        .build())
                .get().build();

        return newCall(request, response -> getAsApiResponse(response, ChecksumsResponse.class));
    }

    @Override
    public Call<Checksums> getChecksums(String filePath) {
        Request request = newRequest()
                .url(apiHost.newBuilder()
                        .addPathSegment("checksumfile")
                        .addQueryParameter("path", String.valueOf(filePath))
                        .build())
                .get().build();

        return newCall(request, response -> getAsApiResponse(response, ChecksumsResponse.class));
    }

    @Override
    public RealApiServiceBuilder newBuilder() {
        return new RealApiServiceBuilder(httpClient, callbackExecutor, progressCallbackThresholdBytes, authenticator, apiHost);
    }

    @Override
    public Executor callbackExecutor() {
        return callbackExecutor;
    }

    @Override
    public Dispatcher dispatcher() {
        return httpClient.dispatcher();
    }

    @Override
    public ConnectionPool connectionPool() {
        return httpClient.connectionPool();
    }

    @Override
    public Cache cache() {
        return httpClient.cache();
    }

    @Override
    public int readTimeoutMs() {
        return httpClient.readTimeoutMillis();
    }

    @Override
    public int writeTimeoutMs() {
        return httpClient.writeTimeoutMillis();
    }

    @Override
    public int connectTimeoutMs() {
        return httpClient.connectTimeoutMillis();
    }

    @Override
    public long progressCallbackThreshold() {
        return progressCallbackThresholdBytes;
    }

    @Override
    public Authenticator authenticator() {
        return authenticator;
    }

    @Override
    public String apiHost() {
        return apiHost.host();
    }

    @Override
    public void shutdown() {
        this.httpClient.connectionPool().evictAll();
        this.httpClient.dispatcher().executorService().shutdownNow();
        closeQuietly(this.httpClient.cache());
    }

    private Request.Builder newRequest() {
        return new Request.Builder().url(apiHost);
    }

    private <T> Call<T> newCall(Request request, ResponseAdapter<T> adapter) {
        Call<T> apiCall = new OkHttpCall<>(httpClient.newCall(request), adapter);
        if (callbackExecutor != null) {
            return new ScheduledCall<>(apiCall, callbackExecutor);
        } else {
            return apiCall;
        }
    }

    private <T extends ApiResponse> T getAsApiResponse(Response response, Class<? extends T> bodyType) throws IOException, ApiError {
        T body = deserializeResponseBody(response, bodyType);
        if (body == null) {
            throw new IOException("API returned an empty response body.");
        }
        if (body.isSuccessful()) {
            return body;
        } else {
            throw new ApiError(body.getStatusCode(), body.getMessage());
        }
    }

    private <T> T deserializeResponseBody(Response response, Class<? extends T> bodyType) throws IOException {
        try {
            if (!response.isSuccessful()) {
                throw new APIHttpException(response.code(), response.message());
            }

            JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(Objects.requireNonNull(response.body()).byteStream())));
            try {
                return gson.fromJson(reader, bodyType);
            } catch (JsonSyntaxException e) {
                throw new IOException("Malformed JSON response.", e);
            } finally {
                closeQuietly(reader);
            }
        } finally {
            closeQuietly(response);
        }
    }

    private Request newDownloadRequest(URL url) {
        return new Request.Builder()
                .url(url)
                .get()
                .build();
    }

    private BufferedSource newDownloadCall(URL url) throws IOException {
        return newDownloadCall(newDownloadRequest(url));
    }

    private BufferedSource newDownloadCall(Request request) throws IOException {
        Response response = httpClient.newCall(request).execute();
        return getAsRawBytes(response);
    }

    private BufferedSource getAsRawBytes(Response response) throws APIHttpException {
        boolean callWasSuccessful = false;
        try {
            if (response.isSuccessful()) {
                callWasSuccessful = true;
                return Objects.requireNonNull(response.body()).source();
            } else {
                throw new APIHttpException(response.code(), response.message());
            }
        } finally {
            if (!callWasSuccessful) {
                closeQuietly(response);
            }
        }
    }

    private boolean isValidPath(String path) {
        return (path != null && !path.isEmpty());
    }

    private void requireValidPath(String path) {
        requireValidPath(path, null);
    }

    private void requireValidPath(String path, String label) {
        if (!isValidPath(path)) {
            if (label != null && !label.isEmpty()) {
                throw new IllegalArgumentException("Path argument `" + label + "` cannot be null or empty.");
            } else {
                throw new IllegalArgumentException("Path argument cannot be null or empty.");
            }
        }
    }
}
