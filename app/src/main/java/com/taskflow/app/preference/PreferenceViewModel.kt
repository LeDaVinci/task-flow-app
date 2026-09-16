package com.taskflow.app.preference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.ChaosQuestApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PreferenceViewModel : ViewModel() {
    private val repository = ChaosQuestApp.instance.preferenceRepository
    val state = repository.state
    private val busy = MutableStateFlow(false)
    val isGenerating = busy.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val errorMessage = error.asStateFlow()
    private var generation: Job? = null

    fun refresh() = action { repository.snapshot() }
    fun setEnabled(enabled: Boolean) {
        generation?.cancel()
        action { repository.setEnabled(enabled) }
    }
    fun adjust(topic: PreferenceTopic, delta: Int) = action { repository.adjust(topic, delta) }
    fun adopt(id: String) = action { repository.adopt(id) }
    fun dismiss() {
        generation?.cancel()
        action { repository.dismissSuggestion() }
    }
    fun clear() {
        generation?.cancel()
        action { repository.clear() }
    }
    fun generate(includeReactions: Boolean) {
        if (busy.value) return
        busy.value = true
        generation = viewModelScope.launch {
            try { repository.generateSuggestion(includeReactions) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "暂时无法保存偏好，请稍后再试。" }
            finally { busy.value = false }
        }
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        error.value = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error.value = "暂时无法保存偏好，请稍后再试。" }
    }
}
