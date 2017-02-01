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
 */

package com.pcloud.sdk.internal.networking;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.pcloud.sdk.RemoteFolder;

public class GetFolderResponse extends ApiResponse {

    @Expose
    @SerializedName("metadata")
    private RemoteFolder folder;

    public RemoteFolder getFolder() {
        return folder;
    }
}
