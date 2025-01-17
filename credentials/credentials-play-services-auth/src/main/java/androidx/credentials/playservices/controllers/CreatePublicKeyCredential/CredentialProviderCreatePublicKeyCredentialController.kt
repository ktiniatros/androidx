/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.credentials.playservices.controllers.CreatePublicKeyCredential

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialUnknownException
import androidx.credentials.playservices.HiddenActivity
import androidx.credentials.playservices.controllers.CredentialProviderController
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions
import java.util.concurrent.Executor

/**
 * A controller to handle the CreatePublicKeyCredential flow with play services.
 *
 * @hide
 */
@Suppress("deprecation")
class CredentialProviderCreatePublicKeyCredentialController(private val activity: Activity) :
        CredentialProviderController<
            CreatePublicKeyCredentialRequest,
            PublicKeyCredentialCreationOptions,
            PublicKeyCredential,
            CreateCredentialResponse,
            CreateCredentialException>(activity) {

    /**
     * The callback object state, used in the protected handleResponse method.
     */
    private lateinit var callback: CredentialManagerCallback<CreateCredentialResponse,
        CreateCredentialException>

    /**
     * The callback requires an executor to invoke it.
     */
    private lateinit var executor: Executor

    private val resultReceiver = object : ResultReceiver(
        Handler(Looper.getMainLooper())
    ) {
        public override fun onReceiveResult(
            resultCode: Int,
            resultData: Bundle
        ) {
            Log.i(
                TAG,
                "onReceiveResult - CredentialProviderCreatePublicKeyCredentialController"
            )
            val isError = resultData.getBoolean(FAILURE_RESPONSE)
            if (isError) {
                val errType = resultData.getString(EXCEPTION_TYPE_TAG)
                Log.i(TAG, "onReceiveResult - error seen: $errType")
                executor.execute { callback.onError(
                    publicKeyCredentialExceptionTypeToException[errType]!!)
                }
            } else {
                val reqCode = resultData.getInt(ACTIVITY_REQUEST_CODE_TAG)
                val resIntent: Intent? = resultData.getParcelable(RESULT_DATA_TAG)
                handleResponse(reqCode, resultCode, resIntent)
            }
        }
    }

    override fun invokePlayServices(
        request: CreatePublicKeyCredentialRequest,
        callback: CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>,
        executor: Executor
    ) {
        this.callback = callback
        this.executor = executor
        val fidoRegistrationRequest: PublicKeyCredentialCreationOptions =
            this.convertRequestToPlayServices(request)

        val hiddenIntent = Intent(activity, HiddenActivity::class.java)
        hiddenIntent.putExtra(REQUEST_TAG, fidoRegistrationRequest)
        generateHiddenActivityIntent(resultReceiver, hiddenIntent,
            CREATE_PUBLIC_KEY_CREDENTIAL_TAG)
        activity.startActivity(hiddenIntent)
    }

    internal fun handleResponse(uniqueRequestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(TAG, "$uniqueRequestCode $resultCode $data")
        if (uniqueRequestCode != CONTROLLER_REQUEST_CODE) {
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            var exception: CreateCredentialException =
                CreatePublicKeyCredentialUnknownException()
            if (resultCode == Activity.RESULT_CANCELED) {
                exception = CreateCredentialCancellationException()
            }
            this.executor.execute { -> this.callback.onError(exception) }
            return
        }
        val bytes: ByteArray? = data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
        if (bytes == null) {
            this.executor.execute { this.callback.onError(
                CreatePublicKeyCredentialUnknownException(
                "Internal error fido module giving null bytes")
            ) }
            return
        }
        val cred: PublicKeyCredential = PublicKeyCredential.deserializeFromBytes(bytes)
        if (PublicKeyCredentialControllerUtility.publicKeyCredentialResponseContainsError(
                this.callback, this.executor, cred)) {
            return
        }
        val response = this.convertResponseToCredentialManager(cred)
        this.executor.execute { this.callback.onResult(response) }
    }

    override fun convertRequestToPlayServices(request: CreatePublicKeyCredentialRequest):
        PublicKeyCredentialCreationOptions {
        return PublicKeyCredentialControllerUtility.convert(request)
    }

    override fun convertResponseToCredentialManager(response: PublicKeyCredential):
        CreateCredentialResponse {
        return CreatePublicKeyCredentialResponse(PublicKeyCredentialControllerUtility
            .toCreatePasskeyResponseJson(response))
    }

    companion object {
        private val TAG = CredentialProviderCreatePublicKeyCredentialController::class.java.name
        private var controller: CredentialProviderCreatePublicKeyCredentialController? = null
        // TODO("Ensure this is tested for multiple calls")

        /**
         * This finds a past version of the
         * [CredentialProviderCreatePublicKeyCredentialController] if it exists, otherwise
         * it generates a new instance.
         *
         * @param activity the calling activity for this controller
         * @return a credential provider controller for CreatePublicKeyCredential
         */
        @JvmStatic
        fun getInstance(activity: Activity):
            CredentialProviderCreatePublicKeyCredentialController {
            if (controller == null) {
                controller = CredentialProviderCreatePublicKeyCredentialController(activity)
            }
            return controller!!
        }
    }
}