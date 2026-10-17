package com.nvr.camnory

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPanelHeightTest {
    @Test fun emptyChatIsCompact() { assertEquals(330, chatPanelHeight(1000, 1000, 180)) }
    @Test fun shortHistoryUsesOnlyNeededSpace() { assertEquals(490, chatPanelHeight(1000, 1000, 490)) }
    @Test fun longHistoryIsCapped() { assertEquals(850, chatPanelHeight(1000, 1000, 2000)) }
    @Test fun keyboardCannotPushPanelBeyondAvailableSpace() { assertEquals(280, chatPanelHeight(1000, 280, 800)) }
}
