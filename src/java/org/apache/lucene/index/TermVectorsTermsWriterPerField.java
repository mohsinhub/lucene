package org.apache.lucene.index;

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

import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;

final class TermVectorsTermsWriterPerField extends TermsHashConsumerPerField {

  final TermVectorsTermsWriterPerThread perThread;
  final TermsHashPerField termsHashPerField;
  final TermVectorsTermsWriter termsWriter;
  final FieldInfo fieldInfo;
  final DocumentsWriter.DocState docState;
  final FieldInvertState fieldState;

  boolean doVectors;
  boolean doVectorPositions;
  boolean doVectorOffsets;

  int maxNumPostings;
  OffsetAttribute offsetAttribute = null;
  
  public TermVectorsTermsWriterPerField(TermsHashPerField termsHashPerField, TermVectorsTermsWriterPerThread perThread, FieldInfo fieldInfo) {
    this.termsHashPerField = termsHashPerField;
    this.perThread = perThread;
    this.termsWriter = perThread.termsWriter;
    this.fieldInfo = fieldInfo;
    docState = termsHashPerField.docState;
    fieldState = termsHashPerField.fieldState;
  }

  @Override
  int getStreamCount() {
    return 2;
  }

  @Override
  boolean start(Fieldable[] fields, int count) {
    doVectors = false;
    doVectorPositions = false;
    doVectorOffsets = false;

    for(int i=0;i<count;i++) {
      Fieldable field = fields[i];
      if (field.isIndexed() && field.isTermVectorStored()) {
        doVectors = true;
        doVectorPositions |= field.isStorePositionWithTermVector();
        doVectorOffsets |= field.isStoreOffsetWithTermVector();
      }
    }

    if (doVectors) {
      if (perThread.doc == null) {
        perThread.doc = termsWriter.getPerDoc();
        perThread.doc.docID = docState.docID;
        assert perThread.doc.numVectorFields == 0;
        assert 0 == perThread.doc.perDocTvf.length();
        assert 0 == perThread.doc.perDocTvf.getFilePointer();
      } else {
        assert perThread.doc.docID == docState.docID;

        if (termsHashPerField.numPostings != 0)
          // Only necessary if previous doc hit a
          // non-aborting exception while writing vectors in
          // this field:
          termsHashPerField.reset();
      }
    }

    // TODO: only if needed for performance
    //perThread.postingsCount = 0;

    return doVectors;
  }     

  public void abort() {}

  /** Called once per field per document if term vectors
   *  are enabled, to write the vectors to
   *  RAMOutputStream, which is then quickly flushed to
   *  * the real term vectors files in the Directory. */
  @Override
  void finish() throws IOException {

    assert docState.testPoint("TermVectorsTermsWriterPerField.finish start");

    final int numPostings = termsHashPerField.numPostings;

    final BytesRef flushTerm = perThread.flushTerm;

    assert numPostings >= 0;

    if (!doVectors || numPostings == 0)
      return;

    if (numPostings > maxNumPostings)
      maxNumPostings = numPostings;

    final IndexOutput tvf = perThread.doc.perDocTvf;

    // This is called once, after inverting all occurrences
    // of a given field in the doc.  At this point we flush
    // our hash into the DocWriter.

    assert fieldInfo.storeTermVector;
    assert perThread.vectorFieldsInOrder(fieldInfo);

    perThread.doc.addField(termsHashPerField.fieldInfo.number);

    // TODO: we may want to make this sort in same order
    // as Codec's terms dict?
    final RawPostingList[] postings = termsHashPerField.sortPostings(BytesRef.getUTF8SortedAsUTF16Comparator());

    tvf.writeVInt(numPostings);
    byte bits = 0x0;
    if (doVectorPositions)
      bits |= TermVectorsReader.STORE_POSITIONS_WITH_TERMVECTOR;
    if (doVectorOffsets) 
      bits |= TermVectorsReader.STORE_OFFSET_WITH_TERMVECTOR;
    tvf.writeByte(bits);

    int lastLen = 0;
    byte[] lastBytes = null;
    int lastStart = 0;
      
    final ByteSliceReader reader = perThread.vectorSliceReader;
    final ByteBlockPool termBytePool = perThread.termsHashPerThread.termBytePool;

    for(int j=0;j<numPostings;j++) {
      final TermVectorsTermsWriter.PostingList posting = (TermVectorsTermsWriter.PostingList) postings[j];
      final int freq = posting.freq;

      // Get BytesRef
      termBytePool.setBytesRef(flushTerm, posting.textStart);

      // Compute common byte prefix between last term and
      // this term
      int prefix = 0;
      if (j > 0) {
        while(prefix < lastLen && prefix < flushTerm.length) {
          if (lastBytes[lastStart+prefix] != flushTerm.bytes[flushTerm.offset+prefix]) {
            break;
          }
          prefix++;
        }
      }

      lastLen = flushTerm.length;
      lastBytes = flushTerm.bytes;
      lastStart = flushTerm.offset;

      final int suffix = flushTerm.length - prefix;
      tvf.writeVInt(prefix);
      tvf.writeVInt(suffix);
      tvf.writeBytes(flushTerm.bytes, lastStart+prefix, suffix);
      tvf.writeVInt(freq);

      if (doVectorPositions) {
        termsHashPerField.initReader(reader, posting, 0);
        reader.writeTo(tvf);
      }

      if (doVectorOffsets) {
        termsHashPerField.initReader(reader, posting, 1);
        reader.writeTo(tvf);
      }
    }

    termsHashPerField.reset();
    perThread.termsHashPerThread.reset(false);
  }

  void shrinkHash() {
    termsHashPerField.shrinkHash(maxNumPostings);
    maxNumPostings = 0;
  }
  
  @Override
  void start(Fieldable f) {
    if (doVectorOffsets) {
      offsetAttribute = fieldState.attributeSource.addAttribute(OffsetAttribute.class);
    } else {
      offsetAttribute = null;
    }
  }

  @Override
  void newTerm(RawPostingList p0) {
    assert docState.testPoint("TermVectorsTermsWriterPerField.newTerm start");
    TermVectorsTermsWriter.PostingList p = (TermVectorsTermsWriter.PostingList) p0;

    p.freq = 1;

    if (doVectorOffsets) {
      int startOffset = fieldState.offset + offsetAttribute.startOffset();
      int endOffset = fieldState.offset + offsetAttribute.endOffset();
      
      termsHashPerField.writeVInt(1, startOffset);
      termsHashPerField.writeVInt(1, endOffset - startOffset);
      p.lastOffset = endOffset;
    }

    if (doVectorPositions) {
      termsHashPerField.writeVInt(0, fieldState.position);
      p.lastPosition = fieldState.position;
    }
  }

  @Override
  void addTerm(RawPostingList p0) {

    assert docState.testPoint("TermVectorsTermsWriterPerField.addTerm start");

    TermVectorsTermsWriter.PostingList p = (TermVectorsTermsWriter.PostingList) p0;
    p.freq++;

    if (doVectorOffsets) {
      int startOffset = fieldState.offset + offsetAttribute.startOffset();
      int endOffset = fieldState.offset + offsetAttribute.endOffset();
      
      termsHashPerField.writeVInt(1, startOffset - p.lastOffset);
      termsHashPerField.writeVInt(1, endOffset - startOffset);
      p.lastOffset = endOffset;
    }

    if (doVectorPositions) {
      termsHashPerField.writeVInt(0, fieldState.position - p.lastPosition);
      p.lastPosition = fieldState.position;
    }
  }

  @Override
  void skippingLongTerm() {}
}
