package com.example.smarty.core.common.util

import android.content.Context
import androidx.annotation.StringRes

class AndroidStringProvider(private val context: Context) : StringProvider {
    override fun getString(@StringRes resId: Int): String {
        return context.getString(resId)
    }

    override fun getString(@StringRes resId: Int, vararg formatArgs: Any): String {
        return context.getString(resId, *formatArgs)
    }
}
