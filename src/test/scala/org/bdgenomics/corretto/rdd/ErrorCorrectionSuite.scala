/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.corretto.rdd

import org.bdgenomics.adam.util.PhredUtils
import org.bdgenomics.corretto.CorrettoFunSuite
import org.bdgenomics.formats.avro.AlignmentRecord
import scala.math.{ abs, pow, log }
import scala.util.Random

class ErrorCorrectionSuite extends CorrettoFunSuite {

  val ec = new ErrorCorrection

  def fpCompare(a: Double, b: Double, epsilon: Double = 1e-3): Boolean = abs(a - b) < epsilon

  test("correct an error in a single read") {
    val ec = new ErrorCorrection

    // seed a random variable
    val rv = new Random(432112344321L)

    // build a reference string
    val ref = (0 until 100).map(i => {
      // generate a base
      rv.nextInt(4) match {
        case 0 => 'A'
        case 1 => 'C'
        case 2 => 'G'
        case _ => 'T'
      }
    }).mkString

    // select a base to change into an error
    val errorBase = rv.nextInt(100)

    var base: Char = 'N'
    do {
      base = rv.nextInt(4) match {
        case 0 => 'A'
        case 1 => 'C'
        case 2 => 'G'
        case _ => 'T'
      }
    } while (base == ref(errorBase))

    // build read string
    val readString = ref.take(errorBase) + base + ref.drop(errorBase + 1)
    val readQual = "." * 100

    // build map
    val pt = ref.sliding(20)
      .map(s => (s, 0.99))
      .toMap

    // start chopping things
    val cutRead = ec.cutRead(AlignmentRecord.newBuilder()
      .setSequence(readString)
      .setQual(readQual)
      .build(), pt, 20, 0.05)

    // correct things
    val correctedRead = ec.correctRead(cutRead, 0.5, '$', pt, 20, 0.05)

    assert(correctedRead.getSequence.toString === ref)
  }

  def createReferenceAndReads(rv: Random,
                              errorRate: Double,
                              referenceLength: Int,
                              skipDistance: Int,
                              readLength: Int): (Seq[AlignmentRecord], String) = {
    // generate reference string
    val refString = (0 until referenceLength).map(i => {
      // generate a base
      rv.nextInt(4) match {
        case 0 => 'A'
        case 1 => 'C'
        case 2 => 'G'
        case _ => 'T'
      }
    }).mkString

    // generate read strings
    val reads = refString.sliding(readLength, skipDistance)
      .zipWithIndex
      .map(kv => {
        val (s, i) = kv

        // "mutate" string
        val (sequence, qual) = s.map(b => {
          if (rv.nextDouble >= errorRate) {
            (b, (PhredUtils.errorProbabilityToPhred(errorRate) + 29 + rv.nextInt(10)).toChar)
          } else {
            (rv.nextInt(4) match {
              case 0 => 'A'
              case 1 => 'C'
              case 2 => 'G'
              case _ => 'T'
            }, (rv.nextInt(8) + 35).toChar)
          }
        }).unzip(p => (p._1, p._2))

        AlignmentRecord.newBuilder()
          .setSequence(sequence.mkString)
          .setQual(qual.mkString)
          .setStart(i * skipDistance)
          .build()
      }).toSeq

    (reads, refString)
  }

  ignore("correct errors for 50x coverage at 100bp, 2% errors") {
    // create a seeded (deterministic) random variable
    val rv = new Random(123321L)

    // create reads and reference
    val (reads, reference) = createReferenceAndReads(rv, 0.02, 1000, 2, 100)

    // correct read errors
    val correctedReads = ErrorCorrection(sc.parallelize(reads),
                                         missingKmerProbability = 0.02, 
                                         ploidy = 1)
    correctedReads.cache()

    // loop and check reads
    val errorRate = correctedReads.map(r => {
      r.getSequence
        .toString
        .zip(reference.drop(r.getStart.toInt).take(100))
        .map(p => {
          if (p._1 == p._2) {
            0.0
          } else {
            1.0
          }
        }).reduce(_ + _) / 100.0
    }).reduce(_ + _) / correctedReads.count().toDouble

    // we should reduce the error rate by about an order of magnitude
    assert(errorRate < 0.003)
    correctedReads.unpersist()
  }
}
