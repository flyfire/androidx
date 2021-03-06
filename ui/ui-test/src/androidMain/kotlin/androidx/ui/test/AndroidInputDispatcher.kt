/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.ui.test

import androidx.compose.ui.node.Owner
import androidx.compose.ui.platform.AndroidOwner
import androidx.ui.test.android.AndroidInputDispatcher

internal actual fun InputDispatcher(owner: Owner): InputDispatcher {
    require(owner is AndroidOwner) {
        "InputDispatcher currently only supports dispatching to AndroidOwner, not to " +
                owner::class.java.simpleName
    }
    val view = owner.view
    return AndroidInputDispatcher { view.dispatchTouchEvent(it) }.apply {
        BaseInputDispatcher.states.remove(owner)?.also {
            // TODO(b/157653315): Move restore state to constructor
            if (it.partialGesture != null) {
                nextDownTime = it.nextDownTime
                gestureLateness = it.gestureLateness
                partialGesture = it.partialGesture
            }
        }
    }
}