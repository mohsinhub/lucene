package org.apache.lucene.index.codecs.standard;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Set;

import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.index.codecs.Codec;
import org.apache.lucene.index.codecs.FieldsConsumer;
import org.apache.lucene.index.codecs.FieldsProducer;
import org.apache.lucene.store.Directory;

/** Default codec. 
 *  @lucene.experimental */
public class StandardCodec extends Codec {

  public StandardCodec() {
    name = "Standard";
  }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    StandardPostingsWriter docs = new StandardPostingsWriterImpl(state);

    // nocommit -- how to gracefully upgrade to a new terms
    // index impl?  could just make a new named codec...
    StandardTermsIndexWriter indexWriter;
    boolean success = false;
    try {
      indexWriter = new SimpleStandardTermsIndexWriter(state);
      success = true;
    } finally {
      if (!success) {
        docs.close();
      }
    }

    success = false;
    try {
      FieldsConsumer ret = new StandardTermsDictWriter(indexWriter, state, docs, BytesRef.getUTF8SortedAsUTF16Comparator());
      success = true;
      return ret;
    } finally {
      if (!success) {
        try {
          docs.close();
        } finally {
          indexWriter.close();
        }
      }
    }
  }

  public final static int TERMS_CACHE_SIZE = 1024;

  @Override
  public FieldsProducer fieldsProducer(Directory dir, FieldInfos fieldInfos, SegmentInfo si, int readBufferSize, int indexDivisor) throws IOException {
    StandardPostingsReader postings = new StandardPostingsReaderImpl(dir, si, readBufferSize);
    StandardTermsIndexReader indexReader;

    boolean success = false;
    try {
      indexReader = new SimpleStandardTermsIndexReader(dir,
                                                       fieldInfos,
                                                       si.name,
                                                       indexDivisor,
                                                       BytesRef.getUTF8SortedAsUTF16Comparator());
      success = true;
    } finally {
      if (!success) {
        postings.close();
      }
    }

    success = false;
    try {
      FieldsProducer ret = new StandardTermsDictReader(indexReader,
                                                       dir, fieldInfos, si.name,
                                                       postings,
                                                       readBufferSize,
                                                       BytesRef.getUTF8SortedAsUTF16Comparator(),
                                                       TERMS_CACHE_SIZE);
      success = true;
      return ret;
    } finally {
      if (!success) {
        try {
          postings.close();
        } finally {
          indexReader.close();
        }
      }
    }
  }

  /** Extension of freq postings file */
  static final String FREQ_EXTENSION = "frq";

  /** Extension of prox postings file */
  static final String PROX_EXTENSION = "prx";

  /** Extension of terms file */
  static final String TERMS_EXTENSION = "tis";

  /** Extension of terms index file */
  static final String TERMS_INDEX_EXTENSION = "tii";

  @Override
  public void files(Directory dir, SegmentInfo segmentInfo, Set<String> files) throws IOException {
    StandardPostingsReaderImpl.files(dir, segmentInfo, files);
    StandardTermsDictReader.files(dir, segmentInfo, files);
    SimpleStandardTermsIndexReader.files(dir, segmentInfo, files);
  }

  @Override
  public void getExtensions(Set<String> extensions) {
    getStandardExtensions(extensions);
  }

  public static void getStandardExtensions(Set<String> extensions) {
    extensions.add(FREQ_EXTENSION);
    extensions.add(PROX_EXTENSION);
    StandardTermsDictReader.getExtensions(extensions);
    SimpleStandardTermsIndexReader.getIndexExtensions(extensions);
  }
}
