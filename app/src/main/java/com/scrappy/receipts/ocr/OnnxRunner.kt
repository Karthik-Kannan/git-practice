package com.scrappy.receipts.ocr

/** A model output: flat data plus the shape needed to index it. */
class Tensor(val data: FloatArray, val shape: LongArray) {

    operator fun get(vararg index: Int): Float {
        var flat = 0
        for (axis in index.indices) {
            flat = flat * shape[axis].toInt() + index[axis]
        }
        return data[flat]
    }

    fun dim(axis: Int): Int = shape[axis].toInt()
}

/**
 * One ONNX session, narrowed to the single call this pipeline makes.
 *
 * Abstracting it here is what lets the same detector, recogniser and decoding
 * code run under onnxruntime-android on a phone and under the desktop
 * onnxruntime JAR in unit tests, with the models and arithmetic unchanged.
 */
interface OnnxRunner {
    fun run(input: FloatArray, shape: LongArray): Tensor
    fun close() {}
}
