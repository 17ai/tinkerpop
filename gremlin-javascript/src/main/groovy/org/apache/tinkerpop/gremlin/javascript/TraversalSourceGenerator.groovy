/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.tinkerpop.gremlin.javascript

import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.util.CoreImports

import java.lang.reflect.Modifier

/**
 * @author Jorge Bay Gondra
 */
class TraversalSourceGenerator {

    public static void create(final String traversalSourceFile) {

        final StringBuilder moduleOutput = new StringBuilder()

        moduleOutput.append("""/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
""")
        moduleOutput.append("""
/**
 * @author Jorge Bay Gondra
 */
'use strict';

var utils = require('../utils');
var parseArgs = utils.parseArgs;
var itemDone = Object.freeze({ value: null, done: true });
var emptyArray = Object.freeze([]);

function Traversal(graph, traversalStrategies, bytecode) {
  this.graph = graph;
  this.traversalStrategies = traversalStrategies;
  this.bytecode = bytecode;
  this.traversers = null;
  this.sideEffects = null;
  this._traversalStrategiesPromise = null;
  this._traversersIteratorIndex = 0;
}

/** @returns {Bytecode} */
Traversal.prototype.getBytecode = function () {
  return this.bytecode;
};

/**
 * Returns an Array containing the traverser objects.
 * @returns {Promise.<Array>}
 */
Traversal.prototype.toList = function () {
  var self = this;
  return this._applyStrategies().then(function () {
    if (!self.traversers || self._traversersIteratorIndex === self.traversers.length) {
      return emptyArray;
    }
    var arr = new Array(self.traversers.length - self._traversersIteratorIndex);
    for (var i = self._traversersIteratorIndex; i < self.traversers.length; i++) {
      arr[i] = self.traversers[i].object;
    }
    self._traversersIteratorIndex = self.traversers.length;
    return arr;
  });
};

/**
 * Async iterator method implementation.
 * Returns a promise containing an iterator item.
 * @returns {Promise.<{value, done}>}
 */
Traversal.prototype.next = function () {
  var self = this;
  return this._applyStrategies().then(function () {
    if (!self.traversers || self._traversersIteratorIndex === self.traversers.length) {
      return itemDone;
    }
    return { value: self.traversers[self._traversersIteratorIndex++].object, done: false };
  });
};

Traversal.prototype._applyStrategies = function () {
  if (this._traversalStrategiesPromise) {
    // Apply strategies only once
    return this._traversalStrategiesPromise;
  }
  return this._traversalStrategiesPromise = this.traversalStrategies.applyStrategies(this);
};

/**
 * Returns the Bytecode JSON representation of the traversal
 * @returns {String}
 */
Traversal.prototype.toString = function () {
  return this.bytecode.toString();
};
  """);

        moduleOutput.append("""
/**
 * Represents an operation.
 * @constructor
 */
function P(operator, value, other) {
  this.operator = operator;
  this.value = value;
  this.other = other;
}

/**
 * Returns the string representation of the instance.
 * @returns {string}
 */
P.prototype.toString = function () {
  if (this.other === undefined) {
    return this.operator + '(' + this.value + ')';
  }
  return this.operator + '(' + this.value + ', ' + this.other + ')';
};

function createP(operator, args) {
  args.unshift(null, operator);
  return new (Function.prototype.bind.apply(P, args));
}
""")
        P.class.getMethods().
                findAll { Modifier.isStatic(it.getModifiers()) }.
                findAll { P.class.isAssignableFrom(it.returnType) }.
                collect { it.name }.
                unique().
                sort { a, b -> a <=> b }.
                each { methodName ->
                    moduleOutput.append(
                            """
/** @param {...Object} args */
P.${SymbolHelper.toJs(methodName)} = function (args) {
  return createP('$methodName', parseArgs.apply(null, arguments));
};
""")
                };
        moduleOutput.append("""
P.prototype.and = function (arg) {
  return new P('and', this, arg);
};

P.prototype.or = function (arg) {
  return new P('or', this, arg);
};
""")

        moduleOutput.append("""
function Traverser(object, bulk) {
  this.object = object;
  this.bulk = bulk == undefined ? 1 : bulk;
}

function TraversalSideEffects() {

}

function toEnum(typeName, keys) {
  var result = {};
  keys.split(' ').forEach(function (k) {
    var jsKey = k;
    if (jsKey === jsKey.toUpperCase()) {
      jsKey = jsKey.toLowerCase();
    }
    result[jsKey] = new EnumValue(typeName, k);
  });
  return result;
}

function EnumValue(typeName, elementName) {
  this.typeName = typeName;
  this.elementName = elementName;
}

module.exports = {
  EnumValue: EnumValue,
  P: P,
  Traversal: Traversal,
  TraversalSideEffects: TraversalSideEffects,
  Traverser: Traverser""")
        for (final Class<? extends Enum> enumClass : CoreImports.getClassImports().
                findAll { Enum.class.isAssignableFrom(it) }.
                sort { a, b -> a.simpleName <=> b.simpleName }) {
            moduleOutput.append(",\n  ${SymbolHelper.decapitalize(enumClass.simpleName)}: " +
                    "toEnum('${SymbolHelper.toJs(enumClass.simpleName)}', '");
            moduleOutput.append(
                enumClass.getEnumConstants().
                        sort { a, b -> a.name() <=> b.name() }.
                        collect { SymbolHelper.toJs(it.name()) }.
                        join(' '));
            moduleOutput.append("\')");
        }

        moduleOutput.append("""\n};""");

        // save to a file
        final File file = new File(traversalSourceFile);
        file.delete()
        moduleOutput.eachLine { file.append(it + "\n") }
    }
}