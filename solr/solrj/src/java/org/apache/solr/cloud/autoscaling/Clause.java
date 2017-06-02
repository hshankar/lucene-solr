/*
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

package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.cloud.autoscaling.Policy.ReplicaInfo;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.cloud.rule.ImplicitSnitch;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;

import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableSet;
import static org.apache.solr.cloud.autoscaling.Clause.TestStatus.PASS;
import static org.apache.solr.cloud.autoscaling.Operand.EQUAL;
import static org.apache.solr.cloud.autoscaling.Operand.GREATER_THAN;
import static org.apache.solr.cloud.autoscaling.Operand.LESS_THAN;
import static org.apache.solr.cloud.autoscaling.Operand.NOT_EQUAL;
import static org.apache.solr.cloud.autoscaling.Operand.WILDCARD;
import static org.apache.solr.cloud.autoscaling.Policy.ANY;
import static org.apache.solr.common.params.CoreAdminParams.COLLECTION;
import static org.apache.solr.common.params.CoreAdminParams.REPLICA;
import static org.apache.solr.common.params.CoreAdminParams.SHARD;

// a set of conditions in a policy
public class Clause implements MapWriter, Comparable<Clause> {
  Map<String, Object> original;
  Condition collection, shard, replica, tag, globalTag;

  boolean strict = true;

  Clause(Map<String, Object> m) {
    this.original = m;
    strict = Boolean.parseBoolean(String.valueOf(m.getOrDefault("strict", "true")));
    Optional<String> globalTagName = m.keySet().stream().filter(Policy.GLOBAL_ONLY_TAGS::contains).findFirst();
    if (globalTagName.isPresent()) {
      globalTag = parse(globalTagName.get(), m);
      if (m.size() > 2) {
        throw new RuntimeException("Only one extra tag supported for the tag " + globalTagName.get() + " in " + Utils.toJSONString(m));
      }
      tag = parse(m.keySet().stream()
          .filter(s -> (!globalTagName.get().equals(s) && !IGNORE_TAGS.contains(s)))
          .findFirst().get(), m);
    } else {
      collection = parse(COLLECTION, m);
      shard = parse(SHARD, m);
      if(m.get(REPLICA) == null){
        throw new RuntimeException(StrUtils.formatString("'replica' is required" + Utils.toJSONString(m)));
      }
      Condition replica = parse(REPLICA, m);
      try {
        int replicaCount = Integer.parseInt(String.valueOf(replica.val));
        if(replicaCount<0){
          throw new RuntimeException("replica value sould be non null "+ Utils.toJSONString(m));
        }
        this.replica = new Condition(replica.name, replicaCount, replica.op);
      } catch (NumberFormatException e) {
        throw new RuntimeException("Only an integer value is supported for replica " + Utils.toJSONString(m));
      }
      m.forEach((s, o) -> parseCondition(s, o));
    }
    if (tag == null)
      throw new RuntimeException("Invalid op, must have one and only one tag other than collection, shard,replica " + Utils.toJSONString(m));

  }

  public boolean doesOverride(Clause that) {
    return (collection.equals(that.collection) &&
        tag.name.equals(that.tag.name));

  }

  public boolean isPerCollectiontag() {
    return globalTag == null;
  }

  void parseCondition(String s, Object o) {
    if (IGNORE_TAGS.contains(s)) return;
    if (tag != null) {
      throw new IllegalArgumentException("Only one tag other than collection, shard, replica is possible");
    }
    tag = parse(s, singletonMap(s, o));
  }

  @Override
  public int compareTo(Clause that) {
    try {
      int v = Integer.compare(this.tag.op.priority, that.tag.op.priority);
      if (v != 0) return v;
      if (this.isPerCollectiontag() && that.isPerCollectiontag()) {
        v = Integer.compare(this.replica.op.priority, that.replica.op.priority);
        if (v == 0) {
          v = Integer.compare((Integer) this.replica.val, (Integer) that.replica.val);
          v = this.replica.op == LESS_THAN ? v : v * -1;
        }
        return v;
      } else {
        return 0;
      }
    } catch (NullPointerException e) {
      throw e;
    }
  }

  void addTags(List<String> params) {
    if (globalTag != null && !params.contains(globalTag.name)) params.add(globalTag.name);
    if (tag != null && !params.contains(tag.name)) params.add(tag.name);
  }

  static class Condition {
    final String name;
    final Object val;
    final Operand op;

    Condition(String name, Object val, Operand op) {
      this.name = name;
      this.val = val;
      this.op = op;
    }

    TestStatus match(Row row) {
      return op.match(val, row.getVal(name));
    }

    TestStatus match(Object testVal) {
      return op.match(this.val, testVal);
    }

    boolean isPass(Object inputVal) {
      return op.match(val, inputVal) == PASS;
    }

    boolean isPass(Row row) {
      return op.match(val, row.getVal(name)) == PASS;
    }

    @Override
    public boolean equals(Object that) {
      if (that instanceof Condition) {
        Condition c = (Condition) that;
        return Objects.equals(c.name, name) && Objects.equals(c.val, val) && c.op == op;
      }
      return false;
    }

    public Integer delta(Object val) {
      return op.delta(this.val, val);
    }
  }

  static Condition parse(String s, Map m) {
    Object expectedVal = null;
    Object val = m.get(s);
    try {
      String conditionName = s.trim();
      String value = val == null ? null : String.valueOf(val).trim();
      Operand operand = null;
      if ((expectedVal = WILDCARD.parse(value)) != null) {
        operand = WILDCARD;
      } else if ((expectedVal = NOT_EQUAL.parse(value)) != null) {
        operand = NOT_EQUAL;
      } else if ((expectedVal = GREATER_THAN.parse(value)) != null) {
        operand = GREATER_THAN;
      } else if ((expectedVal = LESS_THAN.parse(value)) != null) {
        operand = LESS_THAN;
      } else {
        operand = EQUAL;
        expectedVal = EQUAL.parse(value);
      }

      return new Condition(conditionName, expectedVal, operand);

    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid tag : " + s + ":" + val, e);
    }
  }

  public class Violation implements MapWriter {
    final String shard, coll, node;
    final Object actualVal;
    final Integer delta;//how far is the actual value from the expected value
    final Object tagKey;
    private final int hash;


    private Violation(String coll, String shard, String node, Object actualVal, Integer delta, Object tagKey) {
      this.shard = shard;
      this.coll = coll;
      this.node = node;
      this.delta = delta;
      this.actualVal = actualVal;
      this.tagKey = tagKey;
      hash = ("" + coll + " " + shard + " " + node + " " + String.valueOf(tagKey) + " " + Utils.toJSONString(getClause().toMap(new HashMap<>()))).hashCode();
    }

    public Clause getClause() {
      return Clause.this;
    }

    @Override
    public int hashCode() {
      return hash;
    }
    //if the delta is lower , this violation is less serious
    public boolean isLessSerious(Violation that) {
      return that.delta != null && delta != null &&
          Math.abs(delta) < Math.abs(that.delta);
    }

    @Override
    public boolean equals(Object that) {
      if (that instanceof Violation) {
        Violation v = (Violation) that;
        return Objects.equals(this.shard, v.shard) &&
            Objects.equals(this.coll, v.coll) &&
            Objects.equals(this.node, v.node) &&
            Objects.equals(this.tagKey, v.tagKey)
            ;
      }
      return false;
    }

    @Override
    public void writeMap(EntryWriter ew) throws IOException {
      ew.putIfNotNull("collection", coll);
      ew.putIfNotNull("shard", shard);
      ew.putIfNotNull("node", node);
      ew.putIfNotNull("tagKey", String.valueOf(tagKey));
      ew.putIfNotNull("violation", (MapWriter) ew1 -> {
        ew1.put(getClause().isPerCollectiontag() ? "replica" : tag.name,
            String.valueOf(actualVal));
        ew1.putIfNotNull("delta", delta);
      });
      ew.put("clause", getClause());
    }
  }


  public List<Violation> test(List<Row> allRows) {
    List<Violation> violations = new ArrayList<>();
    if (isPerCollectiontag()) {
      Map<String, Map<String, Map<String, AtomicInteger>>> replicaCount = computeReplicaCounts(allRows);
      for (Map.Entry<String, Map<String, Map<String, AtomicInteger>>> e : replicaCount.entrySet()) {
        if (!collection.isPass(e.getKey())) continue;
        for (Map.Entry<String, Map<String, AtomicInteger>> shardVsCount : e.getValue().entrySet()) {
          if (!shard.isPass(shardVsCount.getKey())) continue;
          for (Map.Entry<String, AtomicInteger> counts : shardVsCount.getValue().entrySet()) {
            if (!replica.isPass(counts.getValue())) {
              violations.add(new Violation(
                  e.getKey(),
                  shardVsCount.getKey(),
                  tag.name.equals("node") ? counts.getKey() : null,
                  counts.getValue(),
                  replica.delta(counts.getValue()),
                  counts.getKey()
              ));
            }
          }
        }
      }
    } else {
      for (Row r : allRows) {
        if (!tag.isPass(r)) {
          violations.add(new Violation(null, null, r.node, r.getVal(tag.name), tag.delta(r.getVal(tag.name)), null));
        }
      }
    }
    return violations;

  }


  private Map<String, Map<String, Map<String, AtomicInteger>>> computeReplicaCounts(List<Row> allRows) {
    Map<String, Map<String, Map<String, AtomicInteger>>> collVsShardVsTagVsCount = new HashMap<>();
    for (Row row : allRows)
      for (Map.Entry<String, Map<String, List<ReplicaInfo>>> colls : row.collectionVsShardVsReplicas.entrySet()) {
        String collectionName = colls.getKey();
        if (!collection.isPass(collectionName)) continue;
        collVsShardVsTagVsCount.putIfAbsent(collectionName, new HashMap<>());
        Map<String, Map<String, AtomicInteger>> collMap = collVsShardVsTagVsCount.get(collectionName);
        for (Map.Entry<String, List<ReplicaInfo>> shards : colls.getValue().entrySet()) {
          String shardName = shards.getKey();
          if (ANY.equals(shard.val)) shardName = ANY;
          if (!shard.isPass(shardName)) break;
          collMap.putIfAbsent(shardName, new HashMap<>());
          Map<String, AtomicInteger> tagVsCount = collMap.get(shardName);
          Object tagVal = row.getVal(tag.name);
          tagVsCount.putIfAbsent(tag.isPass(tagVal) ? String.valueOf(tagVal) : "", new AtomicInteger());
          if (tag.isPass(tagVal)) {
            tagVsCount.get(String.valueOf(tagVal)).addAndGet(shards.getValue().size());
          }
        }
      }
    return collVsShardVsTagVsCount;
  }

  public boolean isStrict() {
    return strict;
  }

  @Override
  public String toString() {
    return Utils.toJSONString(original);
  }

  @Override
  public void writeMap(EntryWriter ew) throws IOException {
    for (Map.Entry<String, Object> e : original.entrySet()) ew.put(e.getKey(), e.getValue());
  }

  enum TestStatus {
    NOT_APPLICABLE, FAIL, PASS
  }

  private static final Set<String> IGNORE_TAGS = new HashSet<>(Arrays.asList(REPLICA, COLLECTION, SHARD, "strict"));

  static class ValidateInfo {
    final Class type;
    final Set<String> vals;
    final Long min;
    final Long max;


    ValidateInfo(Class type, Set<String> vals, Long min, Long max) {
      this.type = type;
      this.vals = vals;
      this.min = min;
      this.max = max;
    }
  }


  public static Object validate(String name, Object val) {
    if (val == null) return null;
    ValidateInfo info = validatetypes.get(name);
    if (info == null && name.startsWith(ImplicitSnitch.SYSPROP)) info = validatetypes.get(null);
    if (info == null) throw new RuntimeException("Unknown type :" + name);
    if (info.type == Long.class) {
      Long num = parseNumber(name, val);
      if (info.min != null)
        if (num < info.min) throw new RuntimeException(name + ": " + val + " must be greater than " + info.min);
      if (info.max != null)
        if (num > info.max) throw new RuntimeException(name + ": " + val + " must be less than " + info.max);
      return num;
    } else if (info.type == String.class) {
      if (info.vals != null && !info.vals.contains(val))
        throw new RuntimeException(name + ": " + val + " must be one of " + StrUtils.join(info.vals, ','));
      return val;
    } else {
      throw new RuntimeException("Invalid type ");
    }

  }

  public static Long parseNumber(String name, Object val) {
    if (val == null) return null;
    Number num = 0;
    if (val instanceof String) {
      try {
        num = Long.parseLong((String) val);
      } catch (NumberFormatException e) {
        try {
          num = Double.parseDouble((String) val);
        } catch (NumberFormatException e1) {
          throw new RuntimeException(name + ": " + val + "not a valid number", e);
        }
      }

    } else if (val instanceof Number) {
      num = (Number) val;
    }
    return num.longValue();
  }

  private static final Map<String, ValidateInfo> validatetypes = new HashMap();

  static {
    validatetypes.put("collection", new ValidateInfo(String.class, null, null, null));
    validatetypes.put("shard", new ValidateInfo(String.class, null, null, null));
    validatetypes.put("replica", new ValidateInfo(Long.class, null, 0l, null));
    validatetypes.put(ImplicitSnitch.PORT, new ValidateInfo(Long.class, null, 1024l, 65535l));
    validatetypes.put(ImplicitSnitch.DISK, new ValidateInfo(Long.class, null, 0l, Long.MAX_VALUE));
    validatetypes.put(ImplicitSnitch.NODEROLE, new ValidateInfo(String.class, unmodifiableSet(new HashSet(Arrays.asList("overseer"))), null, null));
    validatetypes.put(ImplicitSnitch.CORES, new ValidateInfo(Long.class, null, 0l, Long.MAX_VALUE));
    validatetypes.put(ImplicitSnitch.SYSLOADAVG, new ValidateInfo(Long.class, null, 0l, 100l));
    validatetypes.put(ImplicitSnitch.HEAPUSAGE, new ValidateInfo(Long.class, null, 0l, Long.MAX_VALUE));
    validatetypes.put(null, new ValidateInfo(String.class, null, null, null));
    for (String ip : ImplicitSnitch.IP_SNITCHES) validatetypes.put(ip, new ValidateInfo(Long.class, null, 0l, 255l));


  }
}