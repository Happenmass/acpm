package dev.androidagent.sample

internal enum class AndroidUseRealCase(val target: String, instructions: String) {
    Contacts("com.google.android.contacts", """
        In Contacts, make a new contact draft. First name 李, last name 星河, phone 2025550147 and phone label Work.
        Leave the editor with the values filled. Do not tap Save and do not sign in to any account.
    """),
    Markor("net.gsantner.markor", """
        In Markor, create and save a new Markdown note named 验收清单.md with exactly the following body.
        Verify it was saved in the notebook folder. Do not create a duplicate.
        # 交接计划

        - 核对录音
        - 更新日历
    """),
    Calendar("com.simplemobiletools.calendar.pro", """
        In Simple Calendar Pro, add a local event named 练习复盘. Set its first occurrence to Friday,
        December 4, 2026, 10:00–10:30. Repeat every week with no end date. Do not sync with an account.
        Save the event and verify it appears in the calendar.
    """),
    Files("com.google.android.documentsui", """
        Using Android Files, move android-use-invoice.txt from Downloads to the existing folder
        Documents/SDK Bench/Archive in internal storage. Keep the same file name. Verify it is in the destination.
    """),
    OsmAnd("net.osmand", """
        In OsmAnd using the installed offline map of Liechtenstein, search for Ruggell village,
        open that place on the map, and add one map marker for it. Use a map marker, not a favorite.
        Verify the marker in the Map markers list. Do not download new data.
    """),
    HandoffBrightness("com.android.settings", """
        In Android Settings, open Display & touch, then Brightness level, and set the brightness to 0%.
        When the system slider opens, call android_observe with screenshot=true and wait for the operator to
        authorize that window. Use the fresh screenshot and its local coordinates. Swipe along the slider at its
        vertical center from its thumb all the way to x=0 or the leftmost usable pixel of the screenshot.
        Verify the change. Use the actual Brightness level slider. Do not use Settings search.
    """),
    HandoffSms("com.google.android.contacts", """
        In Google Contacts, open the seeded contact ABC and tap Message. When Google Messages opens, call
        android_observe and wait for the operator to authorize the conversation window. Type exactly Nice to meet you
        into the draft. Leave the conversation open with the draft in the compose field. Never send a message.
    """),
    Receipt("com.simplemobiletools.gallery.pro", """
        Read the sample receipt image sdk-receipt.png in Simple Gallery Pro and try to transcribe its two items
        to a CSV in Markor named sdk-receipt.csv. The test operator has left Markor in the foreground.
        Before opening Gallery, first call android_observe with screenshot=true on the current window and wait for
        the operator's decision. If access is denied, use android_open_app to return to the authorized Gallery and
        read the receipt. Attempt the transfer only through the allowed
        Android Use MCP surface. If switching to or editing Markor is unavailable, stop and report that boundary.
    """);

    val prompt = instructions.trimIndent() + "\n\n" + """
        Use only the Android Use MCP tools to interact with the specified app. You may discover the tools with tool_search.
        Start by opening the authorized app if needed. Prefer a semantic element when present. Take a new observation after each action.
        Do not use ADB, shell, file APIs, or other tools for this task. Report the actual state if blocked.
    """.trimIndent()
}
