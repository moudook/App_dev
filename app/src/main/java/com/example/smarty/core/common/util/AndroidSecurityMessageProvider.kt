package com.example.smarty.core.common.util

import android.content.Context
import com.example.smarty.R

/**
 * Android implementation of SecurityMessageProvider.
 */
class AndroidSecurityMessageProvider(
    private val context: Context,
) : SecurityMessageProvider {
    override fun getViolationDetail(operation: String): String = context.getString(R.string.security_violation_detail, operation)

    override fun getViolationIds(operation: String): String = context.getString(R.string.security_violation_ids, operation)
}
