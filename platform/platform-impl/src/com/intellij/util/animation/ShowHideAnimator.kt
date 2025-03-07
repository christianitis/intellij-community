// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.animation

import com.intellij.openapi.util.registry.Registry.intValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.DoubleConsumer
import kotlin.math.roundToInt

open class ShowHideAnimator(easing: Easing, private val consumer: DoubleConsumer) {
  private val animator = JBAnimator()
  private val atomicVisible = AtomicBoolean()
  private val statefulEasing = easing.stateful()

  constructor(consumer: DoubleConsumer) : this(Easing.LINEAR, consumer)

  fun setVisible(visible: Boolean, updateVisibility: () -> Unit) {
    if (visible != atomicVisible.getAndSet(visible)) {
      val value = statefulEasing.value
      when {
        !visible && value > 0.0 -> animator.animate(createHidingAnimation(value, updateVisibility))
        visible && value < 1.0 -> animator.animate(createShowingAnimation(value, updateVisibility))
        else -> animator.stop()
      }
    }
  }

  fun setVisibleImmediately(visible: Boolean) {
    animator.stop()
    if (visible != atomicVisible.getAndSet(visible)) {
      consumer.accept(statefulEasing.calc(if (visible) 1.0 else 0.0))
    }
  }

  private val showingDelay
    get() = intValue("ide.animation.showing.delay", 0)

  private val showingDuration
    get() = intValue("ide.animation.showing.duration", 130)

  private val hidingDelay
    get() = intValue("ide.animation.hiding.delay", 140)

  private val hidingDuration
    get() = intValue("ide.animation.hiding.duration", 150)

  private fun createShowingAnimation(value: Double, visibility: () -> Unit) = Animation(consumer).apply {
    if (value > 0.0) {
      duration = (showingDuration * (1 - value)).roundToInt()
      easing = statefulEasing.coerceIn(value, 1.0)
    }
    else {
      delay = showingDelay
      duration = showingDuration
      easing = statefulEasing
    }
  }.runWhenScheduled(visibility)

  private fun createHidingAnimation(value: Double, visibility: () -> Unit) = Animation(consumer).apply {
    if (value < 1.0) {
      duration = (hidingDuration * value).roundToInt()
      easing = statefulEasing.coerceIn(0.0, value).reverse()
    }
    else {
      delay = hidingDelay
      duration = hidingDuration
      easing = statefulEasing.reverse()
    }
  }.runWhenExpiredOrCancelled(visibility)
}
