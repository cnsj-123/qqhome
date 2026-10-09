package com.xiaoming.closie.ui.lifeos.map

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.lifeos.components.LifeEmptyState
import com.xiaoming.closie.ui.lifeos.components.LifePage
import com.xiaoming.closie.ui.lifeos.theme.*

data class MapPlaceUi(
    val sourceId: String, val name: String,
    val latitude: Double? = null, val longitude: Double? = null,
    val linkedDomainLabels: List<String> = emptyList()
)
data class LifeMapUiState(val places: List<MapPlaceUi> = emptyList())

@Composable
fun LifeMapScreen(state: LifeMapUiState = LifeMapUiState(), onBack: () -> Unit) {
    LifePage("生活地图", onBack) {
        Text("生活，散落在这些地方", style = LifeText.title)
        LifeEmptyState("吃喝、购物、衣橱、兴趣、旅行……同一个地点，把相关的记录放在一起。")
        if (state.places.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Text("地图会从真实地点慢慢展开。", style = LifeText.body, color = LocalLifeOsColors.current.inkSecondary)
            LifeEmptyState("当前还没有接入地点记录。地图展示将在后续 Task 接入。")
        } else state.places.forEach {
            Text(it.name, style = LifeText.body)
            Text(it.linkedDomainLabels.joinToString(" · "), style = LifeText.caption)
        }
    }
}
