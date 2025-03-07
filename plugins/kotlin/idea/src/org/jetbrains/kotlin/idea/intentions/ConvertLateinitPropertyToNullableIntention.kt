// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingIntention
import org.jetbrains.kotlin.idea.core.setType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.typeUtil.makeNullable

class ConvertLateinitPropertyToNullableIntention : SelfTargetingIntention<KtProperty>(
    KtProperty::class.java, KotlinBundle.lazyMessage("convert.to.nullable.var")
) {
    override fun isApplicableTo(element: KtProperty, caretOffset: Int): Boolean = element.hasModifier(KtTokens.LATEINIT_KEYWORD)
            && element.isVar
            && element.typeReference?.typeElement !is KtNullableType
            && element.initializer == null

    override fun applyTo(element: KtProperty, editor: Editor?) {
        val typeReference: KtTypeReference = element.typeReference ?: return
        val nullableType = element.analyze(BodyResolveMode.PARTIAL)[BindingContext.TYPE, typeReference]?.makeNullable() ?: return
        element.removeModifier(KtTokens.LATEINIT_KEYWORD)
        element.setType(nullableType)
        element.initializer = KtPsiFactory(element).createExpression(KtTokens.NULL_KEYWORD.value)
    }
}
