package com.amazon.deequ.analyzers

import com.amazon.deequ.{SparkContextSpec, VerificationSuite}
import com.amazon.deequ.analyzers.runners.EmptyStateException
import com.amazon.deequ.checks.{Check, CheckLevel}
import com.amazon.deequ.metrics.DoubleMetric
import com.amazon.deequ.utils.FixtureSupport
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import org.scalatest.{Matchers, WordSpec}

import scala.util.Success

class NullHandlingTests extends WordSpec with Matchers with SparkContextSpec with FixtureSupport {

  private[this] def dataWithNullColumns(session: SparkSession): DataFrame = {

    val schema = StructType(Array(
      StructField("stringCol", StringType, nullable = true),
      StructField("numericCol", DoubleType, nullable = true),
      StructField("numericCol2", DoubleType, nullable = true),
        StructField("numericCol3", DoubleType, nullable = true)
    ))

    val rows = session.sparkContext.parallelize(Seq(
      Row(null, null, null, 1.0),
      Row(null, null, null, 2.0),
      Row(null, null, null, 3.0),
      Row(null, null, null, 4.0),
      Row(null, null, null, 5.0),
      Row(null, null, null, 6.0),
      Row(null, null, null, 7.0),
      Row(null, null, null, 8.0)),
      numSlices = 2)

    session.createDataFrame(rows, schema)
  }

  "Null columns" should {

    "produce correct states" in withSparkSession { session =>

      val data = dataWithNullColumns(session)

      Size().computeStateFrom(data) shouldBe Some(NumMatches(8))
      Completeness("stringCol").computeStateFrom(data) shouldBe Some(NumMatchesAndCount(0, 8))

      Mean("numericCol").computeStateFrom(data) shouldBe None
      StandardDeviation("numericCol").computeStateFrom(data) shouldBe None
      Minimum("numericCol").computeStateFrom(data) shouldBe None
      Maximum("numericCol").computeStateFrom(data) shouldBe None

      DataType("stringCol").computeStateFrom(data) shouldBe
        Some(DataTypeHistogram(8L, 0L, 0L, 0L, 0L))

      Sum("numericCol").computeStateFrom(data) shouldBe None
      ApproxQuantile("numericCol", 0.5).computeStateFrom(data) shouldBe None

      val stringColFrequenciesAndNumRows = CountDistinct("stringCol").computeStateFrom(data)
      assert(stringColFrequenciesAndNumRows.isDefined)

      stringColFrequenciesAndNumRows.get.numRows shouldBe 8
      stringColFrequenciesAndNumRows.get.frequencies.count() shouldBe 0L

      val numericColFrequenciesAndNumRows = MutualInformation("numericCol", "numericCol2")
        .computeStateFrom(data)

      assert(numericColFrequenciesAndNumRows.isDefined)

      numericColFrequenciesAndNumRows.get.numRows shouldBe 8
      numericColFrequenciesAndNumRows.get.frequencies.count() shouldBe 0L


      Correlation("numericCol", "numericCol2").computeStateFrom(data) shouldBe None
    }

    "produce correct metrics" in withSparkSession { session =>

      val data = dataWithNullColumns(session)

      Size().calculate(data).value shouldBe Success(8.0)
      Completeness("stringCol").calculate(data).value shouldBe Success(0.0)

      assertFailedWithEmptyState(Mean("numericCol").calculate(data))

      assertFailedWithEmptyState(StandardDeviation("numericCol").calculate(data))
      assertFailedWithEmptyState(Minimum("numericCol").calculate(data))
      assertFailedWithEmptyState(Maximum("numericCol").calculate(data))

      val dataTypeDistribution = DataType("stringCol").calculate(data).value.get
      dataTypeDistribution.values("Unknown").ratio shouldBe 1.0

      assertFailedWithEmptyState(Sum("numericCol").calculate(data))
      assertFailedWithEmptyState(ApproxQuantile("numericCol", 0.5).calculate(data))

      CountDistinct("stringCol").calculate(data).value shouldBe Success(0.0)
      ApproxCountDistinct("stringCol").calculate(data).value shouldBe Success(0.0)

      assertFailedWithEmptyState(Entropy("stringCol").calculate(data))
      assertFailedWithEmptyState(MutualInformation("numericCol", "numericCol2").calculate(data))
      assertFailedWithEmptyState(MutualInformation("numericCol", "numericCol3").calculate(data))
      assertFailedWithEmptyState(Correlation("numericCol", "numericCol2").calculate(data))
      assertFailedWithEmptyState(Correlation("numericCol", "numericCol3").calculate(data))
    }

    "include analyzer name in EmptyStateExceptions" in withSparkSession { session =>

      val data = dataWithNullColumns(session)

      val metricResult = Mean("numericCol").calculate(data).value

      assert(metricResult.isFailure)

      val exceptionMessage = metricResult.failed.get.getMessage

      assert(exceptionMessage == "Empty state for analyzer Mean(numericCol,None), " +
        "all input values were NULL.")

    }
  }

  private[this] def assertFailedWithEmptyState(metric: DoubleMetric): Unit = {
    assert(metric.value.isFailure)
    assert(metric.value.failed.get.isInstanceOf[EmptyStateException])
  }


}
