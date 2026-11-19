/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * Input of the `finish_answer` MCP tool. The agent names what it used; the server looks
 * each name up as the caller and writes the citation itself.
 */
export interface FinishAnswer {
    /**
     * One line stating the answer, for example the headline number. Shown as the agent's words.
     */
    answer: string;
    /**
     * Glossary terms and metrics the answer used. At most 10.
     */
    concepts?:  ConceptRef[];
    execution?: ExecutionRef;
    /**
     * The user's question.
     */
    question: string;
    /**
     * The SQL the answer rests on. At most 20,000 characters.
     */
    sql?: string;
    /**
     * Tables the answer read. At most 5.
     */
    tables?:     TableRef[];
    timeWindow?: TimeWindowRef;
}

/**
 * A glossary term or metric the answer used.
 */
export interface ConceptRef {
    /**
     * Kind of concept.
     */
    entityType?: ConceptType;
    /**
     * Fully qualified name of the term or metric.
     */
    fqn: string;
    /**
     * The words of the question this concept stands for.
     */
    phrase?: string;
}

/**
 * Kind of concept.
 *
 * Kind of concept an answer binds a phrase to.
 */
export enum ConceptType {
    GlossaryTerm = "glossaryTerm",
    Metric = "metric",
}

/**
 * How the SQL was run, as the agent reports it. OpenMetadata does not run SQL, so a run is
 * always self-reported.
 */
export interface ExecutionRef {
    /**
     * Name of the connector that ran the SQL.
     */
    connector?: string;
    /**
     * Who ran the SQL: none when it was not run, hostConnector when the agent's own warehouse
     * connector ran it.
     */
    executor?: Executor;
    /**
     * Why the SQL was not run.
     */
    reason?: string;
    /**
     * Rows the query returned.
     */
    rowCount?: number;
}

/**
 * Who ran the SQL: none when it was not run, hostConnector when the agent's own warehouse
 * connector ran it.
 *
 * Who ran the SQL. sqlStudio is set only by Collate.
 */
export enum Executor {
    HostConnector = "hostConnector",
    None = "none",
    SQLStudio = "sqlStudio",
}

/**
 * A table the answer read.
 */
export interface TableRef {
    /**
     * Columns the query used.
     */
    columns?: string[];
    /**
     * Fully qualified name of the table.
     */
    fqn: string;
}

/**
 * The time window the answer covers, as the user said it. The server resolves the phrase
 * with the organization's calendar; `start` and `end` are used only when it cannot.
 */
export interface TimeWindowRef {
    /**
     * Last day covered, as yyyy-MM-dd.
     */
    end?: string;
    /**
     * The user's words, for example last month or Q3 FY2026.
     */
    phrase?: string;
    /**
     * First day covered, as yyyy-MM-dd.
     */
    start?: string;
}
