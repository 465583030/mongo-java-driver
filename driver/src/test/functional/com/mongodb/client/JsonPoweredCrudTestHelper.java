/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.client;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationAlternate;
import com.mongodb.client.model.CollationCaseFirst;
import com.mongodb.client.model.CollationMaxVariable;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateManyModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonValue;
import org.junit.AssumptionViolatedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class JsonPoweredCrudTestHelper {
    private final String description;
    private final MongoCollection<BsonDocument> collection;

    public JsonPoweredCrudTestHelper(final String description, final MongoCollection<BsonDocument> collection) {
        this.description = description;
        this.collection = collection;
    }

    BsonDocument getOperationResults(final BsonDocument operation) {
        String name = operation.getString("name").getValue();
        BsonDocument arguments = operation.getDocument("arguments");

        String methodName = "get" + name.substring(0, 1).toUpperCase() + name.substring(1) + "Result";
        try {
            Method method = getClass().getDeclaredMethod(methodName, BsonDocument.class);
            return (BsonDocument) method.invoke(this, arguments);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException("No handler for operation " + methodName);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof AssumptionViolatedException) {
                throw (AssumptionViolatedException) e.getTargetException();
            }
            if (e.getTargetException() instanceof MongoException) {
                throw (MongoException) e.getTargetException();
            }
            throw (RuntimeException) e.getTargetException();
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException("Invalid handler access for operation " + methodName);
        }
    }

    BsonDocument toResult(final int count) {
        return toResult(new BsonInt32(count));
    }

    BsonDocument toResult(final MongoIterable<BsonDocument> results) {
        return toResult(new BsonArray(results.into(new ArrayList<BsonDocument>())));
    }

    BsonDocument toResult(final String key, final BsonValue value) {
        return toResult(new BsonDocument(key, value));
    }

    BsonDocument toResult(final UpdateResult updateResult) {
        BsonDocument resultDoc = new BsonDocument("matchedCount", new BsonInt32((int) updateResult.getMatchedCount()));
        if (updateResult.isModifiedCountAvailable()) {
            resultDoc.append("modifiedCount", new BsonInt32((int) updateResult.getModifiedCount()));
        }
        // If the upsertedId is an ObjectId that means it came from the server and can't be verified.
        // This check is to handle the "ReplaceOne with upsert when no documents match without an id specified" test
        // in replaceOne-pre_2.6
        if (updateResult.getUpsertedId() != null && !updateResult.getUpsertedId().isObjectId()) {
            resultDoc.append("upsertedId", updateResult.getUpsertedId());
        }
        resultDoc.append("upsertedCount", updateResult.getUpsertedId() == null ? new BsonInt32(0) : new BsonInt32(1));

        return toResult(resultDoc);
    }

    BsonDocument toResult(final BulkWriteResult bulkWriteResult) {
        BsonDocument resultDoc = new BsonDocument();
        if (bulkWriteResult.wasAcknowledged()) {
            resultDoc.append("deletedCount", new BsonInt32(bulkWriteResult.getDeletedCount()));
            resultDoc.append("insertedIds", new BsonDocument());
            resultDoc.append("matchedCount", new BsonInt32(bulkWriteResult.getMatchedCount()));
            if (bulkWriteResult.isModifiedCountAvailable()) {
                resultDoc.append("modifiedCount", new BsonInt32(bulkWriteResult.getModifiedCount()));
            }
            resultDoc.append("upsertedCount", bulkWriteResult.getUpserts() == null
                                                      ? new BsonInt32(0) : new BsonInt32(bulkWriteResult.getUpserts().size()));
            resultDoc.append("upsertedIds", new BsonDocument());
        }
        return toResult(resultDoc);
    }

    BsonDocument toResult(final BsonValue results) {
        return new BsonDocument("result", results != null ? results : BsonNull.VALUE);
    }

    BsonDocument getAggregateResult(final BsonDocument arguments) {
        List<BsonDocument> pipeline = new ArrayList<BsonDocument>();
        for (BsonValue stage : arguments.getArray("pipeline")) {
            pipeline.add(stage.asDocument());
        }

        AggregateIterable<BsonDocument> iterable = collection.aggregate(pipeline);
        if (arguments.containsKey("batchSize")) {
            iterable.batchSize(arguments.getNumber("batchSize").intValue());
        }
        if (arguments.containsKey("collation")) {
            iterable.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult(iterable);
    }

    BsonDocument getCountResult(final BsonDocument arguments) {
        CountOptions options = new CountOptions();
        if (arguments.containsKey("skip")) {
            options.skip(arguments.getNumber("skip").intValue());
        }
        if (arguments.containsKey("limit")) {
            options.limit(arguments.getNumber("limit").intValue());
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult((int) collection.count(arguments.getDocument("filter"), options));
    }

    BsonDocument getDistinctResult(final BsonDocument arguments) {
        DistinctIterable<BsonValue> iterable = collection.distinct(arguments.getString("fieldName").getValue(), BsonValue.class);
        if (arguments.containsKey("filter")) {
            iterable.filter(arguments.getDocument("filter"));
        }
        if (arguments.containsKey("collation")) {
            iterable.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult(iterable.into(new BsonArray()));
    }

    @SuppressWarnings("deprecation")
    BsonDocument getFindResult(final BsonDocument arguments) {
        FindIterable<BsonDocument> iterable = collection.find(arguments.getDocument("filter"));
        if (arguments.containsKey("skip")) {
            iterable.skip(arguments.getNumber("skip").intValue());
        }
        if (arguments.containsKey("limit")) {
            iterable.limit(arguments.getNumber("limit").intValue());
        }
        if (arguments.containsKey("batchSize")) {
            iterable.batchSize(arguments.getNumber("batchSize").intValue());
        }
        if (arguments.containsKey("sort")) {
            iterable.sort(arguments.getDocument("sort"));
        }
        if (arguments.containsKey("modifiers")) {
            iterable.modifiers(arguments.getDocument("modifiers"));
        }
        if (arguments.containsKey("collation")) {
            iterable.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult(iterable);
    }

    BsonDocument getDeleteManyResult(final BsonDocument arguments) {
        DeleteOptions options = new DeleteOptions();
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult("deletedCount",
                        new BsonInt32((int) collection.deleteMany(arguments.getDocument("filter"), options).getDeletedCount()));
    }

    BsonDocument getDeleteOneResult(final BsonDocument arguments) {
        DeleteOptions options = new DeleteOptions();
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }

        return toResult("deletedCount",
                        new BsonInt32((int) collection.deleteOne(arguments.getDocument("filter"), options).getDeletedCount()));
    }

    BsonDocument getFindOneAndDeleteResult(final BsonDocument arguments) {
        FindOneAndDeleteOptions options = new FindOneAndDeleteOptions();
        if (arguments.containsKey("projection")) {
            options.projection(arguments.getDocument("projection"));
        }
        if (arguments.containsKey("sort")) {
            options.sort(arguments.getDocument("sort"));
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult(collection.findOneAndDelete(arguments.getDocument("filter"), options));
    }

    BsonDocument getFindOneAndReplaceResult(final BsonDocument arguments) {
        FindOneAndReplaceOptions options = new FindOneAndReplaceOptions();
        if (arguments.containsKey("projection")) {
            options.projection(arguments.getDocument("projection"));
        }
        if (arguments.containsKey("sort")) {
            options.sort(arguments.getDocument("sort"));
        }
        if (arguments.containsKey("upsert")) {
            options.upsert(arguments.getBoolean("upsert").getValue());
        }
        if (arguments.containsKey("returnDocument")) {
            options.returnDocument(arguments.getString("returnDocument").getValue().equals("After") ? ReturnDocument.AFTER
                                                                                                    : ReturnDocument.BEFORE);
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult(collection
                                .findOneAndReplace(arguments.getDocument("filter"), arguments.getDocument("replacement"), options));
    }

    BsonDocument getFindOneAndUpdateResult(final BsonDocument arguments) {
        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions();
        if (arguments.containsKey("projection")) {
            options.projection(arguments.getDocument("projection"));
        }
        if (arguments.containsKey("sort")) {
            options.sort(arguments.getDocument("sort"));
        }
        if (arguments.containsKey("upsert")) {
            options.upsert(arguments.getBoolean("upsert").getValue());
        }
        if (arguments.containsKey("returnDocument")) {
            options.returnDocument(arguments.getString("returnDocument").getValue().equals("After") ? ReturnDocument.AFTER
                                                                                                    : ReturnDocument.BEFORE);
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        if (arguments.containsKey("arrayFilters")) {
            options.arrayFilters((getArrayFilters(arguments.getArray("arrayFilters"))));
        }
        return toResult(collection
                                .findOneAndUpdate(arguments.getDocument("filter"), arguments.getDocument("update"), options));
    }

    BsonDocument getInsertOneResult(final BsonDocument arguments) {
        BsonDocument document = arguments.getDocument("document");
        collection.insertOne(document);
        return toResult(new BsonDocument("insertedId", document.get("_id")));
    }

    BsonDocument getInsertManyResult(final BsonDocument arguments) {
        List<BsonDocument> documents = new ArrayList<BsonDocument>();
        for (BsonValue document : arguments.getArray("documents")) {
            documents.add(document.asDocument());
        }
        collection.insertMany(documents,
                              new InsertManyOptions().ordered(arguments.getBoolean("ordered", BsonBoolean.TRUE).getValue()));
        BsonArray insertedIds = new BsonArray();
        for (BsonDocument document : documents) {
            insertedIds.add(document.get("_id"));
        }
        return toResult(new BsonDocument("insertedIds", insertedIds));
    }

    BsonDocument getReplaceOneResult(final BsonDocument arguments) {
        UpdateOptions options = new UpdateOptions();
        if (arguments.containsKey("upsert")) {
            options.upsert(arguments.getBoolean("upsert").getValue());
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        return toResult(collection
                                .replaceOne(arguments.getDocument("filter"), arguments.getDocument("replacement"), options));
    }

    BsonDocument getUpdateManyResult(final BsonDocument arguments) {
        UpdateOptions options = new UpdateOptions();
        if (arguments.containsKey("upsert")) {
            options.upsert(arguments.getBoolean("upsert").getValue());
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        if (arguments.containsKey("arrayFilters")) {
            options.arrayFilters((getArrayFilters(arguments.getArray("arrayFilters"))));
        }
        return toResult(collection.updateMany(arguments.getDocument("filter"), arguments.getDocument("update"), options));
    }

    @SuppressWarnings("unchecked")
    BsonDocument getUpdateOneResult(final BsonDocument arguments) {
        UpdateOptions options = new UpdateOptions();
        if (arguments.containsKey("upsert")) {
            options.upsert(arguments.getBoolean("upsert").getValue());
        }
        if (arguments.containsKey("collation")) {
            options.collation(getCollation(arguments.getDocument("collation")));
        }
        if (arguments.containsKey("arrayFilters")) {
            options.arrayFilters((getArrayFilters(arguments.getArray("arrayFilters"))));
        }
        return toResult(collection.updateOne(arguments.getDocument("filter"), arguments.getDocument("update"), options));
    }

    BsonDocument getBulkWriteResult(final BsonDocument arguments) {
        WriteConcern writeConcern = WriteConcern.ACKNOWLEDGED;
        if (arguments.containsKey("writeConcern")) {
            if (arguments.getDocument("writeConcern").size() > 1) {
                throw new UnsupportedOperationException("Write concern document contains unexpected keys: "
                                                        + arguments.getDocument("writeConcern").keySet());
            }
            writeConcern = new WriteConcern(arguments.getDocument("writeConcern").getInt32("w").intValue());
        }

        List<WriteModel<BsonDocument>> writeModels = new ArrayList<WriteModel<BsonDocument>>();
        for (BsonValue bsonValue : arguments.getArray("requests")) {
            BsonDocument cur = bsonValue.asDocument();
            if (cur.containsKey("name")) {
                String name = cur.getString("name").getValue();
                BsonDocument requestArguments = cur.getDocument("arguments");
                if (name.equals("insertOne")) {
                    writeModels.add(new InsertOneModel<BsonDocument>(requestArguments.getDocument("document")));
                } else if (name.equals("updateOne")) {
                    writeModels.add(new UpdateOneModel<BsonDocument>(requestArguments.getDocument("filter"),
                                                                            requestArguments.getDocument("update"),
                                                                            getUpdateOptions(requestArguments)));
                } else if (name.equals("updateMany")) {
                    writeModels.add(new UpdateManyModel<BsonDocument>(requestArguments.getDocument("filter"),
                                                                             requestArguments.getDocument("update"),
                                                                             getUpdateOptions(requestArguments)));
                } else {
                    throw new UnsupportedOperationException("Unsupported write request type");
                }
            } else {
                if (cur.get("insertOne") != null) {
                    BsonDocument insertOneArguments = cur.getDocument("insertOne");
                    writeModels.add(new InsertOneModel<BsonDocument>(insertOneArguments.getDocument("document")));
                } else if (cur.get("updateOne") != null) {
                    BsonDocument updateOneArguments = cur.getDocument("updateOne");
                    writeModels.add(new UpdateOneModel<BsonDocument>(updateOneArguments.getDocument("filter"),
                                                                            updateOneArguments.getDocument("update"),
                                                                            getUpdateOptions(updateOneArguments)));
                } else if (cur.get("updateMany") != null) {
                    BsonDocument updateManyArguments = cur.getDocument("updateMany");
                    writeModels.add(new UpdateManyModel<BsonDocument>(updateManyArguments.getDocument("filter"),
                                                                             updateManyArguments.getDocument("update"),
                                                                             getUpdateOptions(updateManyArguments)));
                } else {
                    throw new UnsupportedOperationException("Unsupported write request type");
                }
            }
        }

        return toResult(collection.withWriteConcern(writeConcern).bulkWrite(writeModels,
                                                                            new BulkWriteOptions()
                                                                            .ordered(arguments.getBoolean("ordered", BsonBoolean.TRUE)
                                                                                              .getValue())));

    }

    Collation getCollation(final BsonDocument bsonCollation) {
        Collation.Builder builder = Collation.builder();
        if (bsonCollation.containsKey("locale")) {
            builder.locale(bsonCollation.getString("locale").getValue());
        }
        if (bsonCollation.containsKey("caseLevel")) {
            builder.caseLevel(bsonCollation.getBoolean("caseLevel").getValue());
        }
        if (bsonCollation.containsKey("caseFirst")) {
            builder.collationCaseFirst(CollationCaseFirst.fromString(bsonCollation.getString("caseFirst").getValue()));
        }
        if (bsonCollation.containsKey("strength")) {
            builder.collationStrength(CollationStrength.fromInt(bsonCollation.getInt32("strength").getValue()));
        }
        if (bsonCollation.containsKey("numericOrdering")) {
            builder.numericOrdering(bsonCollation.getBoolean("numericOrdering").getValue());
        }
        if (bsonCollation.containsKey("strength")) {
            builder.collationStrength(CollationStrength.fromInt(bsonCollation.getInt32("strength").getValue()));
        }
        if (bsonCollation.containsKey("alternate")) {
            builder.collationAlternate(CollationAlternate.fromString(bsonCollation.getString("alternate").getValue()));
        }
        if (bsonCollation.containsKey("maxVariable")) {
            builder.collationMaxVariable(CollationMaxVariable.fromString(bsonCollation.getString("maxVariable").getValue()));
        }
        if (bsonCollation.containsKey("normalization")) {
            builder.normalization(bsonCollation.getBoolean("normalization").getValue());
        }
        if (bsonCollation.containsKey("backwards")) {
            builder.backwards(bsonCollation.getBoolean("backwards").getValue());
        }
        return builder.build();
    }

    private UpdateOptions getUpdateOptions(final BsonDocument requestArguments) {
        return new UpdateOptions()
                       .arrayFilters(getArrayFilters(requestArguments.getArray("arrayFilters", null)));
    }

    private List<BsonDocument> getArrayFilters(final BsonArray bsonArray) {
        if (bsonArray == null) {
            return null;
        }
        List<BsonDocument> arrayFilters = new ArrayList<BsonDocument>(bsonArray.size());
        for (BsonValue cur : bsonArray) {
            arrayFilters.add(cur.asDocument());
        }
        return arrayFilters;
    }
}
