// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.inspections.collections

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.getFunctionalClassKind
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.resolve.calls.inference.model.TypeVariableTypeConstructor
import org.jetbrains.kotlin.resolve.calls.model.ReceiverKotlinCallArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedLambdaAtom
import org.jetbrains.kotlin.resolve.calls.model.unwrap
import org.jetbrains.kotlin.resolve.calls.util.getImplicitReceiverValue
import org.jetbrains.kotlin.resolve.calls.tower.NewResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.tower.SubKotlinCallArgumentImpl
import org.jetbrains.kotlin.resolve.calls.tower.receiverValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isSubclassOf
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

fun KotlinType.isFunctionOfAnyKind() = constructor.declarationDescriptor?.getFunctionalClassKind() != null

fun KotlinType?.isMap(builtIns: KotlinBuiltIns): Boolean {
    val classDescriptor = this?.constructor?.declarationDescriptor as? ClassDescriptor ?: return false
    return classDescriptor.name.asString().endsWith("Map") && classDescriptor.isSubclassOf(builtIns.map)
}

fun KotlinType?.isIterable(builtIns: KotlinBuiltIns): Boolean {
    val classDescriptor = this?.constructor?.declarationDescriptor as? ClassDescriptor ?: return false
    return classDescriptor.isListOrSet(builtIns) || classDescriptor.isSubclassOf(builtIns.iterable)
}

fun KotlinType?.isCollection(builtIns: KotlinBuiltIns): Boolean {
    val classDescriptor = this?.constructor?.declarationDescriptor as? ClassDescriptor ?: return false
    return classDescriptor.isListOrSet(builtIns) || classDescriptor.isSubclassOf(builtIns.collection)
}

private fun ClassDescriptor.isListOrSet(builtIns: KotlinBuiltIns): Boolean {
    val className = name.asString()
    return className.endsWith("List") && isSubclassOf(builtIns.list)
            || className.endsWith("Set") && isSubclassOf(builtIns.set)
}

fun KtCallExpression.isCalling(fqName: FqName, context: BindingContext? = null): Boolean {
    return isCalling(listOf(fqName), context)
}

fun KtCallExpression.isCalling(fqNames: List<FqName>, context: BindingContext? = null): Boolean {
    val calleeText = calleeExpression?.text ?: return false
    val targetFqNames = fqNames.filter { it.shortName().asString() == calleeText }
    if (targetFqNames.isEmpty()) return false
    val resolvedCall = getResolvedCall(context ?: safeAnalyzeNonSourceRootCode(BodyResolveMode.PARTIAL)) ?: return false
    return targetFqNames.any { resolvedCall.isCalling(it) }
}

fun ResolvedCall<out CallableDescriptor>.isCalling(fqName: FqName): Boolean {
    return resultingDescriptor.fqNameSafe == fqName
}

fun ResolvedCall<*>.hasLastFunctionalParameterWithResult(context: BindingContext, predicate: (KotlinType) -> Boolean): Boolean {
    val lastParameter = resultingDescriptor.valueParameters.lastOrNull() ?: return false
    val lastArgument = valueArguments[lastParameter]?.arguments?.singleOrNull() ?: return false
    if (this is NewResolvedCallImpl<*>) {
        // TODO: looks like hack
        resolvedCallAtom.subResolvedAtoms?.firstOrNull { it is ResolvedLambdaAtom }.safeAs<ResolvedLambdaAtom>()?.let { lambdaAtom ->
            return lambdaAtom.unwrap().resultArgumentsInfo!!.nonErrorArguments.filterIsInstance<ReceiverKotlinCallArgument>().all {
                val type = it.receiverValue?.type?.let { type ->
                    if (type.constructor is TypeVariableTypeConstructor) {
                        it.safeAs<SubKotlinCallArgumentImpl>()?.valueArgument?.getArgumentExpression()?.getType(context) ?: type
                    } else {
                        type
                    }
                } ?: return@all false
                predicate(type)
            }
        }
    }

    val functionalType = lastArgument.getArgumentExpression()?.getType(context) ?: return false
    // Both Function & KFunction must pass here
    if (!functionalType.isFunctionOfAnyKind()) return false
    val resultType = functionalType.arguments.lastOrNull()?.type ?: return false
    return predicate(resultType)
}

fun KtCallExpression.implicitReceiver(context: BindingContext): ImplicitReceiver? {
    return getResolvedCall(context)?.getImplicitReceiverValue()
}

fun KtCallExpression.receiverType(context: BindingContext): KotlinType? {
    return (getQualifiedExpressionForSelector())?.receiverExpression?.getResolvedCall(context)?.resultingDescriptor?.returnType
        ?: implicitReceiver(context)?.type
}
