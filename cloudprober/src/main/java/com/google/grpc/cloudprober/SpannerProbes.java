/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.grpc.cloudprober;

import com.google.spanner.v1.BeginTransactionRequest;
import com.google.spanner.v1.CommitRequest;
import com.google.spanner.v1.CreateSessionRequest;
import com.google.spanner.v1.DeleteSessionRequest;
import com.google.spanner.v1.ExecuteSqlRequest;
import com.google.spanner.v1.GetSessionRequest;
import com.google.spanner.v1.KeySet;
import com.google.spanner.v1.ListSessionsRequest;
import com.google.spanner.v1.ListSessionsResponse;
import com.google.spanner.v1.PartialResultSet;
import com.google.spanner.v1.PartitionQueryRequest;
import com.google.spanner.v1.PartitionReadRequest;
import com.google.spanner.v1.ReadRequest;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.RollbackRequest;
import com.google.spanner.v1.Session;
import com.google.spanner.v1.SpannerGrpc;
import com.google.spanner.v1.Transaction;
import com.google.spanner.v1.TransactionOptions;
import com.google.spanner.v1.TransactionSelector;
import java.util.Iterator;
import java.util.Map;

/**
 * Probes to probe the testing Spanner database using the blockingstub by grpc and verify the result
 */
public class SpannerProbes {

  private static final String DATABASE = System.getenv("DATABASE");
  private static final String TEST_USERNAME = "test_username";
  private static final String TABLE = "users";

  private SpannerProbes() {}

  private static void deleteSession(SpannerGrpc.SpannerBlockingStub stub, Session session) {
    if (session != null) {
      stub.deleteSession(DeleteSessionRequest.newBuilder().setName(session.getName()).build());
    }
  }

  /**
   * Probes to test session related grpc call from Spanner stub.
   *
   * <p>Includes tests against CreateSession, GetSession, ListSessions, and DeleteSession of Spanner
   * stub.
   */
  public static void sessionManagementProber(
      SpannerGrpc.SpannerBlockingStub stub) throws ProberException {

    Session session = null;

    try {
      session = stub.createSession(CreateSessionRequest.newBuilder().setDatabase(DATABASE).build());

      // Get session.
      Session responseGet =
          stub.getSession(GetSessionRequest.newBuilder().setName(session.getName()).build());

      if (!session.getName().equals(responseGet.getName())) {
        throw new ProberException(
            String.format(
                "Incorrect session name %s, should be %s.",
                responseGet.getName(), session.getName()));
      }

      // List sessions.
      ListSessionsResponse responseList =
          stub.listSessions(ListSessionsRequest.newBuilder().setDatabase(DATABASE).build());

      int inList = 0;
      for (Session s : responseList.getSessionsList()) {
        if (s.getName().equals(session.getName())) {
          inList = 1;
          break;
        }
      }
      if (inList == 0) {
        throw new ProberException(
            String.format("The session list doesn't contain %s.", session.getName()));
      }
    } finally {
      deleteSession(stub, session);
    }
  }

  /** Probes to test ExecuteSql and ExecuteStreamingSql call from Spanner stub. */
  public static void executeSqlProber(
      SpannerGrpc.SpannerBlockingStub stub) throws ProberException {
    Session session = null;
    try {
      session = stub.createSession(CreateSessionRequest.newBuilder().setDatabase(DATABASE).build());

      // Probing executeSql call.
      ResultSet response =
          stub.executeSql(
              ExecuteSqlRequest.newBuilder()
                  .setSession(session.getName())
                  .setSql("select * FROM " + TABLE)
                  .build());

      if (response == null) {
        throw new ProberException("Response is null when executing SQL. ");
      } else if (response.getRowsCount() != 1) {
        throw new ProberException(
            String.format("The number of Responses '%d' is not correct.", response.getRowsCount()));
      } else if (!response
          .getRows(0)
          .getValuesList()
          .get(0)
          .getStringValue()
          .equals(TEST_USERNAME)) {
        throw new ProberException("Response value is not correct when executing SQL.");
      }

      // Probing streaming executeSql call.
      Iterator<PartialResultSet> responsePartial =
          stub.executeStreamingSql(
              ExecuteSqlRequest.newBuilder()
                  .setSession(session.getName())
                  .setSql("select * FROM " + TABLE)
                  .build());

      if (responsePartial == null) {
        throw new ProberException("Response is null when executing streaming SQL. ");
      } else if (!responsePartial.next().getValues(0).getStringValue().equals(TEST_USERNAME)) {
        throw new ProberException("Response value is not correct when executing streaming SQL. ");
      }

    } finally {
      deleteSession(stub, session);
    }
  }

  /** Probe to test Read and StreamingRead grpc call from Spanner stub. */
  public static void readProber(SpannerGrpc.SpannerBlockingStub stub)
      throws ProberException {
    Session session = null;
    try {
      session = stub.createSession(CreateSessionRequest.newBuilder().setDatabase(DATABASE).build());
      KeySet keySet = KeySet.newBuilder().setAll(true).build();

      // Probing read call.
      ResultSet response =
          stub.read(
              ReadRequest.newBuilder()
                  .setSession(session.getName())
                  .setTable(TABLE)
                  .setKeySet(keySet)
                  .addColumns("username")
                  .addColumns("firstname")
                  .addColumns("lastname")
                  .build());

      if (response == null) {
        throw new ProberException("Response is null when executing SQL. ");
      } else if (response.getRowsCount() != 1) {
        throw new ProberException(
            String.format("The number of Responses '%d' is not correct.", response.getRowsCount()));
      } else if (!response
          .getRows(0)
          .getValuesList()
          .get(0)
          .getStringValue()
          .equals(TEST_USERNAME)) {
        throw new ProberException("Response value is not correct when executing Reader.");
      }

      // Probing streamingRead call.
      Iterator<PartialResultSet> responsePartial =
          stub.streamingRead(
              ReadRequest.newBuilder()
                  .setSession(session.getName())
                  .setTable(TABLE)
                  .setKeySet(keySet)
                  .addColumns("username")
                  .addColumns("firstname")
                  .addColumns("lastname")
                  .build());
      if (responsePartial == null) {
        throw new ProberException("Response is null when executing streaming SQL. ");
      } else if (!responsePartial.next().getValues(0).getStringValue().equals(TEST_USERNAME)) {
        throw new ProberException(
            "Response value is not correct when executing streaming Reader. ");
      }

    } finally {
      deleteSession(stub, session);
    }
  }

  /** Probe to test BeginTransaction, Commit and Rollback grpc from Spanner stub. */
  public static void transactionProber(
      SpannerGrpc.SpannerBlockingStub stub) {
    Session session = null;
    try {
      session = stub.createSession(CreateSessionRequest.newBuilder().setDatabase(DATABASE).build());
      // Probing begin transaction call.
      TransactionOptions options =
          TransactionOptions.newBuilder()
              .setReadWrite(TransactionOptions.ReadWrite.getDefaultInstance())
              .build();
      BeginTransactionRequest request =
          BeginTransactionRequest.newBuilder()
              .setSession(session.getName())
              .setOptions(options)
              .build();
      Transaction txn = stub.beginTransaction(request);

      // Probing commit call.
      stub.commit(
          CommitRequest.newBuilder()
              .setSession(session.getName())
              .setTransactionId(txn.getId())
              .build());

      // Probing rollback call.
      txn = stub.beginTransaction(request);
      stub.rollback(
          RollbackRequest.newBuilder()
              .setSession(session.getName())
              .setTransactionId(txn.getId())
              .build());
    } finally {
      deleteSession(stub, session);
    }
  }

  /** Probe to test PartitionQuery and PartitionRead grpc call from Spanner stub. */
  public static void partitionProber(
      SpannerGrpc.SpannerBlockingStub stub) {
    Session session = null;
    try {
      session = stub.createSession(CreateSessionRequest.newBuilder().setDatabase(DATABASE).build());
      // Probing partition query call.
      TransactionOptions options =
          TransactionOptions.newBuilder()
              .setReadOnly(TransactionOptions.ReadOnly.getDefaultInstance())
              .build();
      TransactionSelector selector = TransactionSelector.newBuilder().setBegin(options).build();
      stub.partitionQuery(
          PartitionQueryRequest.newBuilder()
              .setSession(session.getName())
              .setSql("select * FROM " + TABLE)
              .setTransaction(selector)
              .build());

      // Probing partition read call.
      stub.partitionRead(
          PartitionReadRequest.newBuilder()
              .setSession(session.getName())
              .setTable(TABLE)
              .setTransaction(selector)
              .setKeySet(KeySet.newBuilder().setAll(true).build())
              .addColumns("username")
              .addColumns("firstname")
              .addColumns("lastname")
              .build());
    } finally {
      deleteSession(stub, session);
    }
  }

  /** Exception that will be thrown. */
  public static final class ProberException extends Exception {
    ProberException(String s) {
      super(s);
    }
  }
}
