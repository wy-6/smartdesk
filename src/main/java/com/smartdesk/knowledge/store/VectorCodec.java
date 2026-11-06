package com.smartdesk.knowledge.store;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量与字节数组的编解码（Week5 / Day31）。
 *
 * <p>用 float32 小端字节序存 BLOB，而不是 JSON 文本：
 * <ul>
 *   <li>256 维 float32 = 1KB；存成 JSON 文本要 6KB 左右（数字带逗号引号）</li>
 *   <li>读回来直接 {@code ByteBuffer} 解析成 float[]，没有字符串解析开销</li>
 *   <li>数据库里存的是二进制，人也看不懂 —— 正合适，本来就不该给人看</li>
 * </ul>
 *
 * <p><b>为什么统一用小端（Little-Endian）？</b>
 * JDK 的 ByteBuffer 默认就是大端，但 x86/ARM 架构都是小端，
 * 显式指定 {@link ByteOrder#LITTLE_ENDIAN} 可以保证跨平台读出来的字节流一致，
 * 将来换数据库或换机器不会踩坑。
 */
public final class VectorCodec {

	private VectorCodec() {
	}

	/** 向量 → BLOB。 */
	public static byte[] encode(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : vector) {
			buffer.putFloat(value);
		}
		return buffer.array();
	}

	/** BLOB → 向量。 */
	public static float[] decode(byte[] bytes) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		float[] vector = new float[bytes.length / Float.BYTES];
		for (int i = 0; i < vector.length; i++) {
			vector[i] = buffer.getFloat();
		}
		return vector;
	}

	/**
	 * 余弦相似度。
	 *
	 * <p>mock 模型的向量做过 L2 归一化，余弦相似度就等于点积；
	 * 但真实 embedding 不保证归一化，所以这里老老实实算完整公式。
	 *
	 * @param a 向量 a
	 * @param b 向量 b
	 * @return 相似度，范围 [-1, 1]
	 */
	public static double cosine(float[] a, float[] b) {
		if (a == null || b == null || a.length != b.length) {
			return 0;
		}
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * b[i];
			normA += (double) a[i] * a[i];
			normB += (double) b[i] * b[i];
		}
		if (normA == 0 || normB == 0) {
			return 0;
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

}
