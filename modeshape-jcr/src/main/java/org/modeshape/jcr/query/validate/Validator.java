/*
 * ModeShape (http://www.modeshape.org)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors.
 *
 * ModeShape is free software. Unless otherwise indicated, all code in ModeShape
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * 
 * ModeShape is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.modeshape.jcr.query.validate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.jcr.PropertyType;
import javax.jcr.Value;
import javax.jcr.query.qom.Literal;
import org.modeshape.common.collection.Problems;
import org.modeshape.common.i18n.I18n;
import org.modeshape.common.text.Jsr283Encoder;
import org.modeshape.jcr.GraphI18n;
import org.modeshape.jcr.api.query.qom.Operator;
import org.modeshape.jcr.query.QueryContext;
import org.modeshape.jcr.query.model.AllNodes;
import org.modeshape.jcr.query.model.ArithmeticOperand;
import org.modeshape.jcr.query.model.ChildNode;
import org.modeshape.jcr.query.model.ChildNodeJoinCondition;
import org.modeshape.jcr.query.model.Column;
import org.modeshape.jcr.query.model.Comparison;
import org.modeshape.jcr.query.model.DescendantNode;
import org.modeshape.jcr.query.model.DescendantNodeJoinCondition;
import org.modeshape.jcr.query.model.DynamicOperand;
import org.modeshape.jcr.query.model.EquiJoinCondition;
import org.modeshape.jcr.query.model.FullTextSearch;
import org.modeshape.jcr.query.model.FullTextSearchScore;
import org.modeshape.jcr.query.model.Length;
import org.modeshape.jcr.query.model.LowerCase;
import org.modeshape.jcr.query.model.NamedSelector;
import org.modeshape.jcr.query.model.NodeDepth;
import org.modeshape.jcr.query.model.NodeLocalName;
import org.modeshape.jcr.query.model.NodeName;
import org.modeshape.jcr.query.model.NodePath;
import org.modeshape.jcr.query.model.Ordering;
import org.modeshape.jcr.query.model.PropertyExistence;
import org.modeshape.jcr.query.model.PropertyValue;
import org.modeshape.jcr.query.model.Query;
import org.modeshape.jcr.query.model.ReferenceValue;
import org.modeshape.jcr.query.model.SameNode;
import org.modeshape.jcr.query.model.SameNodeJoinCondition;
import org.modeshape.jcr.query.model.SelectorName;
import org.modeshape.jcr.query.model.StaticOperand;
import org.modeshape.jcr.query.model.Subquery;
import org.modeshape.jcr.query.model.TypeSystem;
import org.modeshape.jcr.query.model.UpperCase;
import org.modeshape.jcr.query.model.Visitable;
import org.modeshape.jcr.query.model.Visitor;
import org.modeshape.jcr.query.model.Visitors;
import org.modeshape.jcr.query.model.Visitors.AbstractVisitor;
import org.modeshape.jcr.query.validate.Schemata.Table;
import org.modeshape.jcr.value.Name;
import org.modeshape.jcr.value.Path;
import org.modeshape.jcr.value.ValueFormatException;

/**
 * A {@link Visitor} implementation that validates a query's used of a {@link Schemata} and records any problems as errors.
 */
public class Validator extends AbstractVisitor {

    private final QueryContext context;
    private final Problems problems;
    private final Map<SelectorName, Table> selectorsByNameOrAlias;
    private final Map<SelectorName, Table> selectorsByName;
    private final Map<String, Schemata.Column> columnsByAlias;
    private final boolean validateColumnExistence;

    /**
     * @param context the query context
     * @param selectorsByName the {@link Table tables} by their name or alias, as defined by the selectors
     */
    public Validator( QueryContext context,
                      Map<SelectorName, Table> selectorsByName ) {
        this.context = context;
        this.problems = this.context.getProblems();
        this.selectorsByNameOrAlias = selectorsByName;
        this.selectorsByName = new HashMap<SelectorName, Table>();
        for (Table table : selectorsByName.values()) {
            this.selectorsByName.put(table.getName(), table);
        }
        this.columnsByAlias = new HashMap<String, Schemata.Column>();
        this.validateColumnExistence = context.getHints().validateColumnExistance;
    }

    @Override
    public void visit( AllNodes obj ) {
        // this table doesn't have to be in the list of selected tables
        verifyTable(obj.name());
    }

    @Override
    public void visit( ArithmeticOperand obj ) {
        verifyArithmeticOperand(obj.getLeft());
        verifyArithmeticOperand(obj.getRight());
    }

    protected void verifyArithmeticOperand( DynamicOperand operand ) {
        // The left and right operands must have LONG or DOUBLE types ...
        if (operand instanceof NodeDepth) {
            // good to go
        } else if (operand instanceof Length) {
            // good to go
        } else if (operand instanceof ArithmeticOperand) {
            // good to go
        } else if (operand instanceof FullTextSearchScore) {
            // good to go
        } else if (operand instanceof PropertyValue) {
            PropertyValue value = (PropertyValue)operand;
            SelectorName selector = value.selectorName();
            String propertyName = value.getPropertyName();
            Schemata.Column column = verify(selector, propertyName, this.validateColumnExistence);
            if (column != null) {
                // Check the type ...
                String columnType = column.getPropertyTypeName();
                TypeSystem types = context.getTypeSystem();
                String longType = types.getLongFactory().getTypeName();
                String doubleType = types.getDoubleFactory().getTypeName();
                if (longType.equals(types.getCompatibleType(columnType, longType))) {
                    // Then the column type is long or can be converted to long ...
                } else if (doubleType.equals(types.getCompatibleType(columnType, doubleType))) {
                    // Then the column type is double or can be converted to double ...
                } else {
                    I18n msg = GraphI18n.columnTypeCannotBeUsedInArithmeticOperation;
                    problems.addError(msg, selector, propertyName, columnType);
                }
            }
        } else {
            I18n msg = GraphI18n.dynamicOperandCannotBeUsedInArithmeticOperation;
            problems.addError(msg, operand);
        }
    }

    @Override
    public void visit( ChildNode obj ) {
        verify(obj.selectorName());
        verifyPath(obj.getParentPath());
    }

    @Override
    public void visit( ChildNodeJoinCondition obj ) {
        verify(obj.parentSelectorName());
        verify(obj.childSelectorName());
    }

    @Override
    public void visit( Column obj ) {
        verify(obj.selectorName(), obj.getPropertyName(), this.validateColumnExistence); // don't care about the alias
    }

    @Override
    public void visit( Comparison obj ) {
        // The dynamic operand itself will be visited by the validator as it walks the comparison object.
        verifyComparison(obj.getOperand1(), obj.operator(), obj.getOperand2());
    }

    @Override
    public void visit( DescendantNode obj ) {
        verify(obj.selectorName());
        verifyPath(obj.getAncestorPath());
    }

    @Override
    public void visit( DescendantNodeJoinCondition obj ) {
        verify(obj.ancestorSelectorName());
        verify(obj.descendantSelectorName());
    }

    @Override
    public void visit( EquiJoinCondition obj ) {
        verify(obj.selector1Name(), obj.getProperty1Name(), this.validateColumnExistence);
        verify(obj.selector2Name(), obj.getProperty2Name(), this.validateColumnExistence);
    }

    @Override
    public void visit( FullTextSearch obj ) {
        SelectorName selectorName = obj.selectorName();
        if (obj.getPropertyName() != null) {
            Schemata.Column column = verify(selectorName, obj.getPropertyName(), this.validateColumnExistence);
            if (column != null) {
                // Make sure the column is full-text searchable ...
                if (!column.isFullTextSearchable()) {
                    problems.addError(GraphI18n.columnIsNotFullTextSearchable, column.getName(), selectorName);
                }
            }
        } else {
            Table table = verify(selectorName);
            // Don't need to check if the selector is the '__ALLNODES__' selector ...
            if (table != null && !AllNodes.ALL_NODES_NAME.equals(table.getName())) {
                // Make sure there is at least one column on the table that is full-text searchable ...
                boolean searchable = false;
                for (Schemata.Column column : table.getColumns()) {
                    if (column.isFullTextSearchable()) {
                        searchable = true;
                        break;
                    }
                }
                if (!searchable) {
                    problems.addError(GraphI18n.tableIsNotFullTextSearchable, selectorName);
                }
            }
        }
    }

    @Override
    public void visit( FullTextSearchScore obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( Length obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( LowerCase obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( NamedSelector obj ) {
        verify(obj.aliasOrName());
    }

    @Override
    public void visit( NodeDepth obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( NodeLocalName obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( NodeName obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( NodePath obj ) {
        verify(obj.selectorName());
    }

    @Override
    public void visit( Ordering obj ) {
        verifyOrdering(obj.getOperand());
    }

    @Override
    public void visit( PropertyExistence obj ) {
        verify(obj.selectorName(), obj.getPropertyName(), this.validateColumnExistence);
    }

    @Override
    public void visit( PropertyValue obj ) {
        verify(obj.selectorName(), obj.getPropertyName(), this.validateColumnExistence);
    }

    @Override
    public void visit( ReferenceValue obj ) {
        String propName = obj.getPropertyName();
        if (propName != null) {
            verify(obj.selectorName(), propName, this.validateColumnExistence);
        } else {
            verify(obj.selectorName());
        }
    }

    @Override
    public void visit( Query obj ) {
        // Collect the map of columns by alias for this query ...
        this.columnsByAlias.clear();
        for (Column column : obj.columns()) {
            // Find the schemata column ...
            Table table = tableWithNameOrAlias(column.selectorName());
            if (table != null) {
                Schemata.Column tableColumn = table.getColumn(column.getPropertyName());
                if (tableColumn != null) {
                    this.columnsByAlias.put(column.getColumnName(), tableColumn);
                }
            }
        }
        super.visit(obj);
    }

    @Override
    public void visit( Subquery subquery ) {
        // Don't validate subqueries; this is done as a separate step ...
    }

    @Override
    public void visit( SameNode obj ) {
        verify(obj.selectorName());
        verifyPath(obj.getPath());
    }

    @Override
    public void visit( SameNodeJoinCondition obj ) {
        verify(obj.selector1Name());
        verify(obj.selector2Name());
    }

    protected String readable( Visitable visitable ) {
        return Visitors.readable(visitable, context.getExecutionContext());
    }

    protected void verifyOrdering( DynamicOperand operand ) {
        if (operand instanceof PropertyValue) {
            PropertyValue propValue = (PropertyValue)operand;
            verifyOrdering(propValue.selectorName(), propValue.getPropertyName());
        } else if (operand instanceof ReferenceValue) {
            ReferenceValue value = (ReferenceValue)operand;
            verifyOrdering(value.selectorName(), value.getPropertyName());
        } else if (operand instanceof Length) {
            Length length = (Length)operand;
            verifyOrdering(length.getPropertyValue());
        } else if (operand instanceof LowerCase) {
            verifyOrdering(((LowerCase)operand).getOperand());
        } else if (operand instanceof UpperCase) {
            verifyOrdering(((UpperCase)operand).getOperand());
            // } else if (operand instanceof NodeDepth) {
            // NodeDepth depth = (NodeDepth)operand;
            // verifyOrdering(depth.selectorName(), "mode:depth");
            // } else if (operand instanceof NodePath) {
            // NodePath depth = (NodePath)operand;
            // verifyOrdering(depth.selectorName(), "jcr:path");
            // } else if (operand instanceof NodeLocalName) {
            // NodeLocalName depth = (NodeLocalName)operand;
            // verifyOrdering(depth.selectorName(), "mode:localName");
            // } else if (operand instanceof NodeName) {
            // NodeName depth = (NodeName)operand;
            // verifyOrdering(depth.selectorName(), "jcr:name");
        } else if (operand instanceof ArithmeticOperand) {
            // The LEFT and RIGHT dynamic operands must both work with this operator ...
            ArithmeticOperand arith = (ArithmeticOperand)operand;
            verifyOrdering(arith.getLeft());
            verifyOrdering(arith.getRight());
        }
    }

    protected void verifyOrdering( SelectorName selectorName,
                                   String propertyName ) {
        Schemata.Column column = verify(selectorName, propertyName, false);
        if (column != null && !column.isOrderable()) {
            problems.addError(GraphI18n.columnInTableIsNotOrderable, propertyName, selectorName.getString());
        }
    }

    @SuppressWarnings( "fallthrough" )
    protected void verifyComparison( DynamicOperand operand,
                                     Operator op,
                                     StaticOperand rhs ) {
        if (operand instanceof PropertyValue) {
            PropertyValue propValue = (PropertyValue)operand;
            verifyOperator(propValue.selectorName(), propValue.getPropertyName(), op);
        } else if (operand instanceof NodeName) {
            // Verify that the rhs is convertable to a name ...
            if (rhs instanceof Literal) {
                boolean fail = false;
                // The literal value must be a NAME or URI ...
                Literal literal = (Literal)rhs;
                Value value = literal.getLiteralValue();
                try {
                    String str = value.getString();
                    switch (value.getType()) {
                        case PropertyType.PATH:
                            Path path = context.getExecutionContext().getValueFactories().getPathFactory().create(str);
                            if (path.size() != 1 || path.isAbsolute()) {
                                fail = true;
                                break;
                            } // else continue with the regular processing ...
                        case PropertyType.STRING:
                        case PropertyType.URI:
                        case PropertyType.NAME:
                        case PropertyType.BINARY:
                            try {
                                if (str.startsWith("./") && str.length() > 2) {
                                    // Then it is a URI, and per 3.6.4.9 the './' prefix should be removed ...
                                    str = str.substring(2);
                                }
                                // LIKE operator can have encodeable characters, but others cannot ...
                                if (op != Operator.LIKE) {
                                    // It needs to be convertable to a name ...
                                    Name name = context.getExecutionContext().getValueFactories().getNameFactory().create(str);
                                    if (Jsr283Encoder.containsEncodeableCharacters(name.getLocalName())) {
                                        fail = true;
                                    }
                                }
                            } catch (ValueFormatException e) {
                                // nope ...
                                fail = true;
                            } catch (IllegalArgumentException e) {
                                // nope ...
                                fail = true;
                            }
                            break;
                        default:
                            fail = true;
                            break;
                    }
                } catch (javax.jcr.RepositoryException e) {
                    // nope ...
                    fail = true;
                }
                if (fail) {
                    problems.addError(GraphI18n.nameOperandRequiresNameLiteralType, readable(operand), op.symbol(), readable(rhs));
                }
            }
        } else if (operand instanceof ReferenceValue) {
            ReferenceValue value = (ReferenceValue)operand;
            verifyOperator(value.selectorName(), value.getPropertyName(), op);
        } else if (operand instanceof Length) {
            Length length = (Length)operand;
            verifyComparison(length.getPropertyValue(), op, rhs);
            // Verify that the rhs is a long or convertable to a long ...
            if (rhs instanceof Literal) {
                try {
                    ((Literal)rhs).getLiteralValue().getLong();
                } catch (javax.jcr.RepositoryException e) {
                    // nope ...
                    problems.addError(GraphI18n.lengthOperandRequiresLongLiteralType,
                                      readable(operand),
                                      op.symbol(),
                                      readable(rhs));
                }
            }
        } else if (operand instanceof LowerCase) {
            verifyComparison(((LowerCase)operand).getOperand(), op, rhs);
        } else if (operand instanceof UpperCase) {
            verifyComparison(((UpperCase)operand).getOperand(), op, rhs);
            // } else if (operand instanceof NodeDepth) {
            // NodeDepth depth = (NodeDepth)operand;
            // verifyOperator(depth.selectorName(), "mode:depth", op);
            // } else if (operand instanceof NodePath) {
            // NodePath depth = (NodePath)operand;
            // verifyOperator(depth.selectorName(), "jcr:path", op);
            // } else if (operand instanceof NodeLocalName) {
            // NodeLocalName depth = (NodeLocalName)operand;
            // verifyOperator(depth.selectorName(), "mode:localName", op);
            // } else if (operand instanceof NodeName) {
            // NodeName depth = (NodeName)operand;
            // verifyOperator(depth.selectorName(), "jcr:name", op);
        } else if (operand instanceof ArithmeticOperand) {
            // The LEFT and RIGHT dynamic operands must both work with this operator ...
            ArithmeticOperand arith = (ArithmeticOperand)operand;
            verifyComparison(arith.getLeft(), op, rhs);
            verifyComparison(arith.getRight(), op, rhs);
        }
    }

    protected void verifyOperator( SelectorName selectorName,
                                   String propertyName,
                                   Operator op ) {
        Schemata.Column column = verify(selectorName, propertyName, false);
        if (column != null) {
            if (!column.getOperators().contains(op)) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Operator allowed : column.getOperators()) {
                    if (first) first = false;
                    else sb.append(", ");
                    sb.append(allowed.symbol());
                }
                problems.addError(GraphI18n.operatorIsNotValidAgainstColumnInTable,
                                  op.symbol(),
                                  propertyName,
                                  selectorName.getString(),
                                  sb);
            }
        }
    }

    protected Table tableWithNameOrAlias( SelectorName tableName ) {
        Table table = selectorsByNameOrAlias.get(tableName);
        if (table == null) {
            // Try looking up the table by it's real name (if an alias were used) ...
            table = selectorsByName.get(tableName);
        }
        return table; // may be null
    }

    protected Table verify( SelectorName selectorName ) {
        Table table = tableWithNameOrAlias(selectorName);
        if (table == null) {
            problems.addError(GraphI18n.tableDoesNotExist, selectorName.name());
        }
        return table; // may be null
    }

    protected Table verifyTable( SelectorName tableName ) {
        Table table = tableWithNameOrAlias(tableName);
        if (table == null) {
            problems.addError(GraphI18n.tableDoesNotExist, tableName.name());
        }
        return table; // may be null
    }

    protected void verifyPath( String pathStr ) {
        try {
            Path path = context.getExecutionContext().getValueFactories().getPathFactory().create(pathStr);
            if (!path.isAbsolute()) {
                problems.addError(GraphI18n.pathIsNotAbsolute, pathStr);
            }
        } catch (IllegalArgumentException e) {
            problems.addError(GraphI18n.pathIsNotValid, pathStr);
        } catch (ValueFormatException e) {
            problems.addError(GraphI18n.pathIsNotValid, pathStr);
        }
    }

    protected Schemata.Column verify( SelectorName selectorName,
                                      String propertyName,
                                      boolean columnIsRequired ) {
        Table table = tableWithNameOrAlias(selectorName);
        if (table == null) {
            StringBuilder existingSelectors = new StringBuilder();
            boolean first = true;
            for (SelectorName sel : selectorsByNameOrAlias.keySet()) {
                if (first) first = false;
                else existingSelectors.append(", ");
                existingSelectors.append("'").append(sel.getString()).append("'");
            }
            problems.addError(GraphI18n.tableDoesNotExistButMatchesAnotherTable, selectorName.name(), existingSelectors);
            return null;
        }
        Schemata.Column column = table.getColumn(propertyName);
        if (column == null) {
            // Maybe the supplied property name is really an alias ...
            column = this.columnsByAlias.get(propertyName);
            boolean propertyNameIsWildcard = propertyName == null || "*".equals(propertyName);
            if (column == null && !propertyNameIsWildcard) {
                if (!table.hasExtraColumns() && columnIsRequired) {
                    problems.addError(GraphI18n.columnDoesNotExistOnTable, propertyName, selectorName.name());
                    checkVariationsOfPropertyName(selectorName, propertyName, table, problems);
                } else {
                    if (!checkVariationsOfPropertyName(selectorName, propertyName, table, problems)) {
                        problems.addWarning(GraphI18n.columnDoesNotExistOnTableAndMayBeResidual,
                                            propertyName,
                                            selectorName.name());
                    }
                }
            }
        }
        return column; // may be null
    }

    protected boolean checkVariationsOfPropertyName( SelectorName selector,
                                                     String propertyName,
                                                     Table actualTable,
                                                     Problems problems ) {
        Set<String> vars = new HashSet<String>();
        vars.add(propertyName);

        // Now add some common misspellings ...
        vars.add(propertyName.replace('.', ':')); // period instead of colon
        vars.add(propertyName.replace('_', ':')); // underscore instead of colon
        if ("jcr:mixinType".equalsIgnoreCase(propertyName) || "jcr.mixinType".equalsIgnoreCase(propertyName)) {
            vars.add("jcr:mixinTypes");
        }
        if ("jcr:uid".equalsIgnoreCase(propertyName) || "jcr:uuuid".equalsIgnoreCase(propertyName)) {
            vars.add("jcr:uuid");
        }

        // Look to see if any of these variations can be found on any of the selectors ...
        boolean found = false;
        for (SelectorName selectorName : selectorsByNameOrAlias.keySet()) {
            Table table = tableWithNameOrAlias(selectorName);
            for (String var : vars) {
                if (table != null && table.getColumn(var) != null) {
                    if (table == actualTable) {
                        problems.addWarning(GraphI18n.columnDoesNotExistOnTableAndMayBeTypo, propertyName, selector.name(), var);
                        found = true;
                    } else {
                        problems.addWarning(GraphI18n.columnDoesNotExistOnTableAndMayBeWrongSelector,
                                            propertyName,
                                            selector.name(),
                                            var,
                                            selectorName.name());
                        found = true;
                    }
                }
            }
        }
        return found;
    }
}
