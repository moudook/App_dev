package com.example.smarty.ui.viewmodel

import com.example.smarty.core.common.util.ContentSecurityFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewModelTest {
    @Test
    fun `security filter wraps user content`() {
        val wrapped = ContentSecurityFilter.wrapUserContent("User message here")

        assertEquals(true, wrapped.contains("--- BEGIN USER CONTENT ---"))
        assertEquals(true, wrapped.contains("--- END USER CONTENT ---"))
        assertEquals(true, wrapped.contains("User message here"))
    }
}
