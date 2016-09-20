/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
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

package org.apache.hadoop.util;

import java.nio.ByteBuffer;

public class BytesUtil {
	public static byte[] long2byteArray(long l) {
		byte b[] = new byte[8];

		ByteBuffer buf = ByteBuffer.wrap(b);
		buf.putLong(l);
		return b;
	}	

	public static long byteArray2long(byte[] b) {
		ByteBuffer buf = ByteBuffer.wrap(b);
		return buf.getLong();
	}
}