/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.optimization

import java.util.Random

import breeze.linalg.{DenseVector, SparseVector, norm}
import breeze.optimize.FirstOrderMinimizer.{FunctionValuesConverged, GradientConverged}
import org.apache.spark.Logging
import org.apache.spark.broadcast.Broadcast
import org.mockito.Mockito._
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function.TwiceDiffFunction
import com.linkedin.photon.ml.normalization.{NoNormalization, NormalizationContext}
import com.linkedin.photon.ml.test.SparkTestUtils

/**
 * Verify that core optimizers do reasonable things on small test problems.
 */
class OptimizerIntegTest extends SparkTestUtils with Logging {
  @DataProvider(parallel = true)
  def optimzersUsingInitialValue(): Array[Array[Object]] = {
    Array(
      Array(new LBFGS(
        tolerance = OptimizerIntegTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerIntegTest.MAX_ITERATIONS,
        normalizationContext = OptimizerIntegTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerIntegTest.ENABLE_TRACKING)),
      Array(new TRON(
        tolerance = OptimizerIntegTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerIntegTest.MAX_ITERATIONS,
        normalizationContext = OptimizerIntegTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerIntegTest.ENABLE_TRACKING)))
  }

  @DataProvider(parallel = true)
  def optimzersNotUsingInitialValue(): Array[Array[Object]] = {
    Array(
      Array(new LBFGS(
        tolerance = OptimizerIntegTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerIntegTest.MAX_ITERATIONS,
        normalizationContext = OptimizerIntegTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerIntegTest.ENABLE_TRACKING,
        isReusingPreviousInitialState = false)),
      Array(new TRON(
        tolerance = OptimizerIntegTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerIntegTest.MAX_ITERATIONS,
        normalizationContext = OptimizerIntegTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerIntegTest.ENABLE_TRACKING,
        isReusingPreviousInitialState = false)))
  }

  // TODO: Currently the test objective function used by this test ignores weights, so testing points with varying
  //       weights is pointless

  @Test(dataProvider = "optimzersUsingInitialValue")
  def checkEasyTestFunctionSparkNoInitialValue(optimizer: Optimizer[TwiceDiffFunction]): Unit =
    sparkTest("checkEasyTestFunctionSparkNoInitialValue") {
      val features = new SparseVector[Double](Array(), Array(), OptimizerIntegTest.PROBLEM_DIMENSION)

      // Test unweighted sample
      val pt = new LabeledPoint(label = 1, features, offset = 0, weight = 1)
      val data = sc.parallelize(Seq(pt))
      optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1))(data)
      OptimizerIntegTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)

      // Test weighted sample
      val pt2 = new LabeledPoint(label = 1, features, offset = 0, weight = 1.5)
      val data2 = sc.parallelize(Seq(pt2))
      optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1))(data2)
      OptimizerIntegTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)
    }

  @Test(dataProvider = "optimzersNotUsingInitialValue")
  def checkEasyTestFunctionSparkInitialValue(optimizer: Optimizer[TwiceDiffFunction]): Unit =
    sparkTest("checkEasyTestFunctionSparkInitialValue") {
      val features = new SparseVector[Double](Array(), Array(), OptimizerIntegTest.PROBLEM_DIMENSION)
      val r = new Random(OptimizerIntegTest.RANDOM_SEED)

      // Test unweighted sample
      val pt = new LabeledPoint(label = 1, features, offset = 0, weight = 1)
      val data = sc.parallelize(Seq(pt))
      for (iter <- 0 to OptimizerIntegTest.RANDOM_SAMPLES) {
        val initParam = DenseVector.fill[Double](OptimizerIntegTest.PROBLEM_DIMENSION)(r.nextDouble())
        optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1), initParam)(data)

        assertTrue(optimizer.getStateTracker.isDefined)
        assertTrue(optimizer.isDone)
        OptimizerIntegTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)
      }

    // Test weighted sample
    val pt2 = new LabeledPoint(label = 1, features, offset = 0, weight = 0.5)
    val data2 = sc.parallelize(Seq(pt2))
    for (iter <- 0 to OptimizerIntegTest.RANDOM_SAMPLES) {
      val initParam = DenseVector.fill[Double](OptimizerIntegTest.PROBLEM_DIMENSION)(r.nextDouble())
      optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1), initParam)(data2)

      OptimizerIntegTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)
    }
  }
}

object OptimizerIntegTest extends Logging {
  val PROBLEM_DIMENSION: Int = 10
  val MAX_ITERATIONS: Int = 1000 * PROBLEM_DIMENSION
  val CONVERGENCE_TOLERANCE: Double = 1e-12
  val OBJECTIVE_TOLERANCE: Double = 1e-6
  val GRADIENT_TOLERANCE: Double = 1e-6
  val PARAMETER_TOLERANCE: Double = 1e-4
  val MONOTONICITY_TOLERANCE: Double = 1e-6
  val RANDOM_SEED: Long = 314159265359L
  val RANDOM_SAMPLES: Int = 100
  val ENABLE_TRACKING: Boolean = true
  val NORMALIZATION = NoNormalization()
  val NORMALIZATION_MOCK = mock(classOf[Broadcast[NormalizationContext]])

  doReturn(NORMALIZATION).when(NORMALIZATION_MOCK).value

  def checkConvergence(history: OptimizationStatesTracker) {
    var lastValue: Double = Double.MaxValue

    history.getTrackedStates.foreach { state =>
      assertTrue(lastValue >= state.value, "Objective should be monotonically decreasing (current=[" + state.value +
        "], previous=[" + lastValue + "])")
      lastValue = state.value
    }
  }

  /**
   * Common checks for the easy test function:
   * <ul>
   * <li>Did we get the expected parameters?</li>
   * <li>Did we get the expected objective?</li>
   * <li>Did we see monotonic convergence?</li>
   * </ul>
   */
  private def easyOptimizationStatesChecks(optimizerStatesTracker: OptimizationStatesTracker): Unit = {

    logInfo(s"Optimizer state: $optimizerStatesTracker")

    // The optimizer should be converged
    assertTrue(optimizerStatesTracker.converged)
    assertFalse(optimizerStatesTracker.getTrackedTimeHistory.isEmpty)
    assertFalse(optimizerStatesTracker.getTrackedStates.isEmpty)
    assertEquals(optimizerStatesTracker.getTrackedStates.length, optimizerStatesTracker.getTrackedTimeHistory.length)

    val optimizedObj = optimizerStatesTracker.getTrackedStates.last.value
    val optimizedGradientNorm = norm(optimizerStatesTracker.getTrackedStates.last.gradient, 2)
    val optimizedParam = optimizerStatesTracker.getTrackedStates.last.coefficients

    if (optimizerStatesTracker.convergenceReason.forall(_ == FunctionValuesConverged)) {
      // Expected answer in terms of optimal objective
      assertEquals(
        optimizedObj,
        0,
        OBJECTIVE_TOLERANCE,
        s"Optimized objective should be very close to zero (eps=[$OBJECTIVE_TOLERANCE])")
    } else if (optimizerStatesTracker.convergenceReason.forall(_ == GradientConverged)) {
      // Expected answer in terms of optimal gradient
      assertEquals(
        optimizedGradientNorm,
        0,
        GRADIENT_TOLERANCE,
        s"Optimized gradient norm should be very close to zero (eps=[$GRADIENT_TOLERANCE])")
    }

    // Expected answer in terms of optimal parameters
    optimizedParam.foreachPair { (idx, x) =>
      assertEquals(
        x,
        IntegTestObjective.CENTROID,
        PARAMETER_TOLERANCE,
        s"Optimized parameter for index [$idx] should be close to TestObjective.CENTROID (eps=[$PARAMETER_TOLERANCE]")
    }

    // Monotonic convergence
    checkConvergence(optimizerStatesTracker)
  }
}
