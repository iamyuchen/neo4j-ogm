/*
 * Copyright (c) 2002-2022 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.ogm.autoindex;

import static java.util.Collections.*;
import static java.util.regex.Pattern.*;
import static org.neo4j.ogm.autoindex.IndexType.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.neo4j.ogm.request.Statement;
import org.neo4j.ogm.session.request.RowDataStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an Index that can be auto generated in Neo4j.
 *
 * @author Mark Angrish
 * @author Eric Spiegelberg
 */
class AutoIndex {

    private static final Logger logger = LoggerFactory.getLogger(AutoIndex.class);

    private static final Set<String> ENTITIES_IN_LOOKUP_INDIZES = Collections
        .unmodifiableSet(new HashSet<>(Arrays.asList("node", "relationship")));

    private static final String REL_ENTITY_TYPE = "RELATIONSHIP";

    private final String[] properties;

    /**
     * Owning type - either node label or relationship type
     */
    private final String owningType;

    /**
     * Type of the index/constraint
     */
    private final IndexType type;

    /**
     * This is a cypher fragment that is used during create and drop for constraints.
     */
    private final String description;

    private String indexName;

    AutoIndex(IndexType type, String owningType, String[] properties) {
        this.properties = properties;
        this.owningType = owningType;
        this.type = type;
        this.description = createDescription(type, owningType, properties);
    }

    private static String createDescription(IndexType type, String owningType, String[] properties) {

        String name = owningType.toLowerCase();

        switch (type) {
            case NODE_SINGLE_INDEX:
                validatePropertiesLength(properties, NODE_SINGLE_INDEX);
                return "INDEX ON :`" + owningType + "`(`" + properties[0] + "`)";

            case REL_SINGLE_INDEX:
                validatePropertiesLength(properties, REL_SINGLE_INDEX);
                return "INDEX FOR ()-[`" + name + "`:`" + owningType + "`]-() ON (`" + name + "`.`" + properties[0] + "`)";

            case UNIQUE_CONSTRAINT:
                validatePropertiesLength(properties, UNIQUE_CONSTRAINT);
                return "CONSTRAINT ON (`" + name + "`:`" + owningType + "`) ASSERT `" + name + "`.`" + properties[0]
                    + "` IS UNIQUE";

            case NODE_COMPOSITE_INDEX:
                return buildCompositeIndex(name, owningType, properties);

            case REL_COMPOSITE_INDEX:
                return buildRelCompositeIndex(name, owningType, properties);

            case NODE_KEY_CONSTRAINT:
                return buildNodeKeyConstraint(name, owningType, properties);

            case NODE_PROP_EXISTENCE_CONSTRAINT:
                validatePropertiesLength(properties, NODE_PROP_EXISTENCE_CONSTRAINT);
                return "CONSTRAINT ON (`" + name + "`:`" + owningType + "`) ASSERT exists(`" + name + "`.`"
                    + properties[0] + "`)";

            case REL_PROP_EXISTENCE_CONSTRAINT:
                validatePropertiesLength(properties, NODE_PROP_EXISTENCE_CONSTRAINT);
                return "CONSTRAINT ON ()-[`" + name + "`:`" + owningType + "`]-() ASSERT exists(`" + name + "`.`"
                    + properties[0] + "`)";

            default:
                throw new UnsupportedOperationException("Index type " + type + " not supported yet");
        }
    }

    private static void validatePropertiesLength(String[] properties, IndexType violatedIndexType) {

        if (properties.length != 1) {
            throw new IllegalArgumentException(
                violatedIndexType + " must have exactly one property, got " +
                    Arrays.toString(properties));
        }
    }

    private static String buildCompositeIndex(String name, String owningType, String[] properties) {

        StringBuilder sb = new StringBuilder();
        sb.append("INDEX ON :`")
            .append(owningType)
            .append("`(");
        appendProperties(sb, properties);
        sb.append(")");
        return sb.toString();
    }

    private static String buildRelCompositeIndex(String name, String owningType, String[] properties) {
        StringBuilder sb = new StringBuilder();
        sb.append("INDEX FOR ()-[`")
            .append(name)
            .append("`:`")
            .append(owningType)
            .append("`]-() ON (");
        appendPropertiesWithNode(sb, name, properties);
        sb.append(")");
        return sb.toString();
    }

    private static String buildNodeKeyConstraint(String name, String owningType, String[] properties) {

        StringBuilder sb = new StringBuilder();
        sb.append("CONSTRAINT ON (`")
            .append(name)
            .append("`:`")
            .append(owningType)
            .append("`) ASSERT (");
        appendPropertiesWithNode(sb, name, properties);
        sb.append(") IS NODE KEY");
        return sb.toString();
    }

    private static void appendProperties(StringBuilder sb, String[] properties) {
        for (int i = 0; i < properties.length; i++) {
            sb.append('`');
            sb.append(properties[i]);
            sb.append('`');
            if (i < (properties.length - 1)) {
                sb.append(',');
            }
        }
    }

    private static void appendPropertiesWithNode(StringBuilder sb, String nodeName, String[] properties) {
        for (int i = 0; i < properties.length; i++) {
            sb.append('`');
            sb.append(nodeName);
            sb.append("`.`");
            sb.append(properties[i]);
            sb.append('`');
            if (i < (properties.length - 1)) {
                sb.append(',');
            }
        }
    }

    public String[] getProperties() {
        return properties;
    }

    public String getOwningType() {
        return owningType;
    }

    public IndexType getType() {
        return type;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    Statement getCreateStatement() {
        return new RowDataStatement("CREATE " + this.description, emptyMap());
    }

    public Statement getDropStatement() {
        switch (type) {
            case REL_SINGLE_INDEX:
            case REL_COMPOSITE_INDEX:
                return new RowDataStatement("DROP INDEX " + this.indexName, emptyMap());
            default:
                return new RowDataStatement("DROP " + this.description, emptyMap());
        }
    }

    String getDescription() {
        return description;
    }

    static Optional<AutoIndex> parseConstraint(Map<String, Object> constraintRow, String version) {

        Pattern pattern;
        Matcher matcher;

        String name = constraintRow.containsKey("name") ? (String) constraintRow.get("name") : null;
        String description = (String) constraintRow.get("description");
        if (isPriorTo4x(version)) {

            pattern = compile(
                "CONSTRAINT ON \\((?<name>.*):(?<label>.*)\\) ASSERT ?\\k<name>.(?<property>.*) IS UNIQUE");
            matcher = pattern.matcher(description);
            if (matcher.matches()) {
                String label = matcher.group("label").trim();
                String[] properties = matcher.group("property").split(",");
                return Optional.of(new AutoIndex(IndexType.UNIQUE_CONSTRAINT, label, properties));
            }

            pattern = compile(
                "CONSTRAINT ON \\((?<name>.*):(?<label>.*)\\) ASSERT \\((?<properties>.*)\\) IS NODE KEY");
            matcher = pattern.matcher(description);
            if (matcher.matches()) {
                String label = matcher.group("label").trim();
                String[] properties = matcher.group("properties").split(",");
                for (int i = 0; i < properties.length; i++) {
                    properties[i] = properties[i].trim().substring(label.length() + 1);
                }
                return Optional.of(new AutoIndex(IndexType.NODE_KEY_CONSTRAINT, label, properties));
            }

            pattern = compile(
                "CONSTRAINT ON \\(\\s?(?<name>.*):(?<label>.*)\\s?\\) ASSERT exists\\(?\\k<name>.(?<property>.*)\\)");
            matcher = pattern.matcher(description);
            if (matcher.matches()) {
                String label = matcher.group("label").trim();
                String[] properties = matcher.group("property").split(",");
                return Optional.of(new AutoIndex(IndexType.NODE_PROP_EXISTENCE_CONSTRAINT, label, properties));
            }

            pattern = compile(
                "CONSTRAINT ON \\(\\)-\\[\\s?(?<name>.*):(?<label>.*)\\s?\\]-\\(\\) ASSERT exists\\(?\\k<name>.(?<property>.*)\\)");
            matcher = pattern.matcher(description);
            if (matcher.matches()) {
                String label = matcher.group("label").trim();
                String[] properties = matcher.group("property").split(",");
                for (int i = 0; i < properties.length; i++) {
                    properties[i] = properties[i].trim();
                }
                return Optional.of(new AutoIndex(IndexType.REL_PROP_EXISTENCE_CONSTRAINT, label, properties));
            }

            logger.warn("Could not parse constraint description {}", description);
        }

        pattern = compile(
            "CONSTRAINT ON \\( ?(?<name>.+):(?<label>.+) ?\\) ASSERT \\(\\k<name>\\.(?<property>.*)\\) IS UNIQUE");
        matcher = pattern.matcher(description);
        if (matcher.matches()) {
            String label = matcher.group("label").trim();
            String[] properties = matcher.group("property").split(",");
            return Optional.of(new AutoIndex(IndexType.UNIQUE_CONSTRAINT, label, properties));
        }

        pattern = compile(
            "CONSTRAINT ON \\((?<name>.*):(?<label>.*)\\) ASSERT \\((?<properties>.*)\\) IS NODE KEY");
        matcher = pattern.matcher(description);
        if (matcher.matches()) {
            String label = matcher.group("label").trim();
            String[] properties = matcher.group("properties").split(",");
            for (int i = 0; i < properties.length; i++) {
                properties[i] = properties[i].trim().substring(label.length() + 1);
            }
            return Optional.of(new AutoIndex(IndexType.NODE_KEY_CONSTRAINT, label, properties));
        }

        pattern = compile(
            "CONSTRAINT ON \\(\\s?(?<name>.*):(?<label>.*)\\s?\\) ASSERT (?:exists)?\\(?\\k<name>.(?<property>.*)\\)(?: IS NOT NULL)?");
        matcher = pattern.matcher(description);
        if (matcher.matches()) {
            String label = matcher.group("label").trim();
            String[] properties = matcher.group("property").split(",");
            return Optional.of(new AutoIndex(IndexType.NODE_PROP_EXISTENCE_CONSTRAINT, label, properties));
        }

        pattern = compile(
            "CONSTRAINT ON \\(\\)-\\[\\s?(?<name>.*):(?<label>.*)\\s?]-\\(\\) ASSERT (?:exists)?\\(?\\k<name>.(?<property>.*)\\)(?: IS NOT NULL)?");
        matcher = pattern.matcher(description);
        if (matcher.matches()) {
            String label = matcher.group("label").trim();
            String[] properties = matcher.group("property").split(",");
            for (int i = 0; i < properties.length; i++) {
                properties[i] = properties[i].trim();
            }
            return Optional.of(new AutoIndex(IndexType.REL_PROP_EXISTENCE_CONSTRAINT, label, properties));
        }

        logger.warn("Could not parse constraint description {}", description);
        return Optional.empty();
    }

    static boolean isFulltext(Map<String, Object> indexRow) {

        String indexType = (String) indexRow.get("type");

        return indexType != null && indexType.toLowerCase(Locale.ENGLISH).contains("fulltext");
    }

    static boolean isNodeOrRelationshipLookup(Map<String, Object> indexRow) {

        String indexType = (String) indexRow.get("type");
        String entityType = (String) indexRow.get("entityType");

        return indexType != null && entityType != null &&
            indexType.toLowerCase(Locale.ENGLISH).trim().equals("lookup") && ENTITIES_IN_LOOKUP_INDIZES
            .contains(entityType.trim().toLowerCase(Locale.ENGLISH));
    }

    static Optional<AutoIndex> parseIndex(Map<String, Object> indexRow, String version) {

        Pattern pattern;
        Matcher matcher;

        String description = (String) indexRow.get("description");
        String indexType = (String) indexRow.get("type");

        if (isFulltext(indexRow)) {
            logger.warn("Ignoring unsupported fulltext index.");
            return Optional.empty();
        }

        if (isNodeOrRelationshipLookup(indexRow)) {
            logger.info("The Node and Relationship lookups available in Neo4j 4.3+ should not be modified and Neo4j-OGM wont touch it.");
            return Optional.empty();
        }

        if (isPriorTo4x(version)) {
            // skip unique properties index because they will get processed within
            // the collection of constraints.
            if (indexType.equals("node_unique_property")) {
                return Optional.empty();
            }

            pattern = compile("INDEX ON :(?<label>.*)\\((?<property>.*)\\)");
            matcher = pattern.matcher(description);
            if (matcher.matches()) {
                String label = matcher.group("label");
                String[] properties = matcher.group("property").split(",");
                for (int i = 0; i < properties.length; i++) {
                    properties[i] = properties[i].trim();
                }
                if (properties.length > 1) {
                    return Optional.of(new AutoIndex(IndexType.NODE_COMPOSITE_INDEX, label, properties));
                } else {
                    return Optional.of(new AutoIndex(NODE_SINGLE_INDEX, label, properties));
                }
            }
        }

        // skip unique properties index because they will get processed within
        // the collection of constraints.
        if (indexRow.containsKey("uniqueness")) {
            String indexUniqueness = (String) indexRow.get("uniqueness");
            if (indexUniqueness.equals("UNIQUE")) {
                return Optional.empty();
            }
        }

        if (indexRow.containsKey("properties") && indexRow.containsKey("labelsOrTypes") && indexRow.get("labelsOrTypes") instanceof String[]) {
            String[] indexProperties = (String[]) indexRow.get("properties");
            String indexLabelOrType = ((String[]) indexRow.get("labelsOrTypes"))[0];
            String entityType = (String) indexRow.get("entityType");
            AutoIndex autoIndex;
            if (REL_ENTITY_TYPE.equalsIgnoreCase(entityType)) {
                String indexName = (String) indexRow.get("name");
                autoIndex = new AutoIndex(indexProperties.length > 1 ? REL_COMPOSITE_INDEX : REL_SINGLE_INDEX,
                    indexLabelOrType, indexProperties);
                autoIndex.setIndexName(indexName);
            } else {
                autoIndex = new AutoIndex(indexProperties.length > 1 ? NODE_COMPOSITE_INDEX : NODE_SINGLE_INDEX,
                    indexLabelOrType, indexProperties);
            }

            return Optional.of(autoIndex);
        }

        logger.warn("Could not parse index of type {} with description {}", indexType, description);
        return Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AutoIndex autoIndex = (AutoIndex) o;
        return Arrays.equals(properties, autoIndex.properties) &&
            owningType.equals(autoIndex.owningType) &&
            type == autoIndex.type;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(owningType, type);
        result = 31 * result + Arrays.hashCode(properties);
        return result;
    }

    @Override
    public String toString() {
        return "AutoIndex{" +
            "description='" + description + '\'' +
            '}';
    }

    public boolean hasOpposite() {
        switch (type) {
            case NODE_SINGLE_INDEX:
            case NODE_COMPOSITE_INDEX:
            case UNIQUE_CONSTRAINT:
            case NODE_KEY_CONSTRAINT:
                return true;

            default:
                return false;
        }
    }

    public AutoIndex createOppositeIndex() {
        switch (type) {
            case NODE_SINGLE_INDEX:
                return new AutoIndex(UNIQUE_CONSTRAINT, owningType, properties);

            case UNIQUE_CONSTRAINT:
                return new AutoIndex(NODE_SINGLE_INDEX, owningType, properties);

            case NODE_COMPOSITE_INDEX:
                return new AutoIndex(NODE_KEY_CONSTRAINT, owningType, properties);

            case NODE_KEY_CONSTRAINT:
                return new AutoIndex(NODE_COMPOSITE_INDEX, owningType, properties);

            default:
                throw new IllegalStateException("Can not create opposite index for type=" + type);
        }
    }

    static boolean isPriorTo4x(String version) {
        return version.compareTo("4.0") < 0;
    }
}
