package com.financeautomation.presentation.screens.drawer

object ReorderLogic {

    fun calculateEffectiveRowHeight(measuredHeightPx: Float, defaultHeightPx: Float): Float {
        return if (measuredHeightPx > 0f) measuredHeightPx else defaultHeightPx
    }

    fun calculateSwapThreshold(rowHeightPx: Float): Float {
        return rowHeightPx * 0.5f
    }

    data class DragStepResult<T>(
        val updatedList: List<T>,
        val remainingOffsetY: Float,
        val didSwap: Boolean
    )

    fun <T> processDragStep(
        currentList: List<T>,
        draggingId: String,
        currentOffsetY: Float,
        rowHeightPx: Float,
        swapThresholdPx: Float,
        idSelector: (T) -> String
    ): DragStepResult<T> {
        val currentIndex = currentList.indexOfFirst { idSelector(it) == draggingId }
        if (currentIndex == -1) {
            return DragStepResult(currentList, currentOffsetY, false)
        }

        if (currentOffsetY > swapThresholdPx && currentIndex < currentList.size - 1) {
            val mutable = currentList.toMutableList()
            val item = mutable.removeAt(currentIndex)
            mutable.add(currentIndex + 1, item)
            return DragStepResult(mutable, currentOffsetY - rowHeightPx, true)
        } else if (currentOffsetY < -swapThresholdPx && currentIndex > 0) {
            val mutable = currentList.toMutableList()
            val item = mutable.removeAt(currentIndex)
            mutable.add(currentIndex - 1, item)
            return DragStepResult(mutable, currentOffsetY + rowHeightPx, true)
        }

        return DragStepResult(currentList, currentOffsetY, false)
    }

    fun hasOrderChanged(finalIds: List<String>, referenceIds: List<String>): Boolean {
        return finalIds != referenceIds
    }

    fun <T> resolveCancelReset(latestUpstreamList: List<T>): List<T> {
        return latestUpstreamList
    }
}
