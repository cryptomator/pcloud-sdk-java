# Unofficial, unaffiliated and unassociated fork of https://github.com/pCloud/pcloud-sdk-java for internal use in Cryptomator for Android.

> [!IMPORTANT]  
> This project is provided under the terms of the [Apache License, Version 2.0;](LICENSE) while it is made available to the public,
> it is important to note that this repository is **not intended for public use** and therefore **no support** is provided whatsoever.
>
> For a project designed for use by the public, please refer to the [original repository.](https://github.com/pCloud/pcloud-sdk-java)
>
> An excerpt from the README of the original project is reproduced below for your convenience and to provide proper attribution.  
> This project is not affiliated, associated, endorsed by, or in any way officially connected with pCloud, or any of its subsidiaries or its affiliates.

---

# Excerpt from the original README

## Requirements

- Java 7.0+
- Android 2.3+ (API9+)

## Documentation

- The documentation for the SDK can be found [here](https://pcloud.github.io/pcloud-sdk-java/).
- The pCloud API documentation can be found [here][docs].

## Getting started
### 1. Create a pCloud account
  
  - Visit [pCloud's website](https://www.pcloud.com/) and create an account.

### 2. Register your pCloud API application

  - Login to the [pCloud Developer Site](https://docs.pcloud.com/)

  - Create an application in the [pCloud App Console Page](https://docs.pcloud.com/oauth/index.html).
<br/><img src="docs/screenshot_my_applications.png" width="640"/>
<br/><img src="docs/screenshot_new_app.png" width="640"/>

  - Take note of the app key(client ID) of your application once you create it.
<br/><img src="docs/screenshot_client_id.png" width="640"/>

### 3. Configure your pCloud API application

  - Add a publisher.
  - Add a description.
  - Add a redirect URI in the `Redirect URIs` field in your application configuration page.

  >The SDK expects `pcloud-oauth://{your.application.package}` custom Uri to be added in the Application configuration page.

  - Optionally add an icon that will be displayed to users upon authorization requests.
  - Turn on the `Allow implicit grant` option.
  - Save the changes.
  <br/><img src="docs/screenshot_app_configuration.png" width="640"/>


## Basics

### Creating a Client

``` java
ApiClient apiClient = PCloudSdk.newClientBuilder()
                .authenticator(Authenticators.newOAuthAuthenticator(<your OAuth access token here>))
                // Other configuration...
                .create();
```
- `ApiClient` instances should be reused as much as possible. Avoid creating multiple instances.
- Existing `ApiClient` instances can be re-configured by calling `ApiClient.newBuilder()` and creating a new instance.
- Existing `ApiClient` instances can be 'killed' by calling `ApiClient.shutdown()`.
- For details on the available configuration options, see [here](https://pcloud.github.io/pcloud-sdk-java/com/pcloud/sdk/ApiClient.Builder.html)

---
### Making API calls

#### Creating a `Call`:

```java
Call<RemoteFolder> call = apiClient.listFolder(RemoteFolder.ROOT_FOLDER_ID);
```
  - Creating `Call` instances does not by itself make any API request, treat the objects as a declaration of intent. See the following sections for more information on how to execute calls.
  - For a full list of available API calls, see [here](https://pcloud.github.io/pcloud-sdk-java/com/pcloud/sdk/ApiClient.html).

#### Executing a `Call` and obtaining the result on the same thread:
```java
RemoteFolder folder = call.execute();
```
  - `Call.execute()` will execute the call on the calling thread, blocking it until a response is delivered, a timeout is reached or an error occurs.
  - `Call.execute()` will throw an `IOException` on a networking error and `ApiError` on error returned by pCloud's API.
  - Request timeouts can be controlled via the methods in `ApiClient.Builder`
  - **Avoid calling `Call.execute()` from the UI thread, as it can potentially block for an extended period of time. On Android applications targetting API11+ , calling this method from the main thread will cause a `NetworkOnMainThreadException`.**

#### Executing a `Call` asynchronously:
```java
call.enqueue(new Callback<RemoteFolder>() {
                @Override
                public void onResponse(Call<RemoteFolder> call, RemoteFolder response) {
                    // Successful response
                }

                @Override
                public void onFailure(Call<RemoteFolder> call, Throwable t) {
                    // Call failed with an error.
                }
            });
```
  - `Call.enqueue()` will return immediately, the actual work will scheduled on another thread.
  - `Callback.onResponse()` will be called on a successful response.
  - `Callback.onFailure()` will be called if an error occurs during the execution of the call.
  - By default `Callback` methods will be called on an arbitrary thread, to control this behavior see `ApiClient.Builder.callbackExecutor(Executor)`

#### Reusing `Call` instances:
 - A 'Call' instance should be used only once, that is any further call to `Call.execute()` or 'Call.enqueue()' after the former have been already called, will lead to a runtime exception.
 - Calls can be reused by calling `Call.clone()` that will create a new, identical object, that can be used to make a new API request.
```java
Call<RemoteFolder> newCall = call.clone();
```
---
### File operations

#### List folder
```java
// Get direct children.
RemoteFolder folder = apiClient.listFolder(<The folder id of target folder>);

// Get direct children and other files recursively. (slower and potentialy memory-intensive).
RemoteFolder folder = apiClient.listFolder(<The folder id of target folder>, true);
```
or
```java
RemoteFolder folder = ...;
// Update folder and children information.
RemoteFolder updatedFolder = folder.update();
// Update folder and children information, including all child entries.
RemoteFolder updatedFolder = folder.update(true);
```

#### Copy File

```java
RemoteFile file = ...;
RemoteFolder destinationFolder = ...
// Copy file to folder, will block.
RemoteFile copiedFile = file.copy(destinationFolder);
```
or
```java
ApiClient apiClient =...;
RemoteFile file = ...;
RemoteFolder destinationFolder = ...

//Execute call immediately, call will block;
RemoteFile copiedFile = apiClient.copyFile(file, destinationFolder).execute();

// Execute call asynchronously
apiClient.copyFile(file, destinationFolder).enqueue(new Callback<RemoteFile>() {
                @Override
                public void onResponse(Call<RemoteFile> call, RemoteFile copiedFile) {
                    // Successful response
                }

                @Override
                public void onFailure(Call<RemoteFile> call, Throwable t) {
                    // Call failed with an error.
                }
            });
```
#### Upload a file
- Uploading a local file to pCloud:

```java
ApiClient apiClient =...;
File localFile = ...;

RemoteFile uploadedFile = apiClient.createFile(
	RemoteFolder.ROOT_FOLDER_ID,
    localFile.getName(),
    DataSource.create(localFile),
    new Date(localFile.lastModified())
        .execute();
```

- Uploading a local file to pCloud with progress notifications:

```java
ApiClient apiClient =...;
File localFile = ...;
ProgressListener listener = new ProgressListener() {
	public void onProgress(long done, long total) {
    	System.out.format("\rUploading... %.1f\n", ((double) done / (double) total) * 100d);
	}
};

RemoteFile uploadedFile = apiClient.createFile(
        RemoteFolder.ROOT_FOLDER_ID,
        localFile.getName(),
        DataSource.create(localFile),
        new Date(localFile.lastModified(),
        listener)
	.execute();
```

#### Download a file

- Download a remote file to a local folder:

```java
RemoteFile remoteFile = ...;
File localFolder = ...;
File localFile = new File(localFolder, remoteFile.name());
remoteFile.download(DataSink.create(localFile));
```


- Download a remote file to a local folder with download progress notifications:

```java
RemoteFile remoteFile = ...;
File localFolder = ...;
File localFile = new File(localFolder, remoteFile.name());
ProgressListener listener = new ProgressListener() {
	public void onProgress(long done, long total) {
    	System.out.format("\Downloading... %.1f\n", ((double) done / (double) total) * 100d);
	}
};

remoteFile.download(DataSink.create(localFile), listener);
```

- Download a remote file to an arbitrary destination:

```java
RemoteFile remoteFile = ...;
remoteFile.download(new DataSink() {
	@Override
	public void readAll(BufferedSource source) throws IOException {
		// Read bytes from source.
        source.readByte();

		// Read bytes with an InputStream:
        source.inputStream().read();
	}});
```


# License
	Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.



[site]: https://www.pcloud.com/
[docs]: https://docs.pcloud.com/