package org.globsframework.mcp.jsonschema;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.metamodel.GlobTypeBuilder;
import org.globsframework.core.metamodel.GlobTypeBuilderFactory;
import org.globsframework.core.metamodel.annotations.*;
import org.globsframework.core.metamodel.fields.*;
import org.globsframework.json.GSonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GlobJsonSchemaTest {

    @Test
    public void scalarsAndAnnotations() {
        assertEquals(GSonUtils.normalize("""
                {
                  "type": "object",
                  "description": "An order to place",
                  "properties": {
                    "reference": {"type": "string", "description": "The order reference", "maxLength": 12},
                    "quantity": {"type": "integer"},
                    "price": {"type": "number"},
                    "currency": {"type": "string", "enum": ["EUR", "USD"]},
                    "urgent": {"type": "boolean"},
                    "wantedFor": {"type": "string", "format": "date"},
                    "tags": {"type": "array", "items": {"type": "string"}}
                  },
                  "required": ["reference", "quantity"]
                }"""),
                GSonUtils.normalize(GlobJsonSchema.toSchemaJson(Order.TYPE)));
    }

    @Test
    public void nestedTypeGoesToDefs() {
        assertEquals(GSonUtils.normalize("""
                {
                  "type": "object",
                  "properties": {
                    "customer": {"$ref": "#/$defs/Customer"},
                    "lines": {"type": "array", "items": {"$ref": "#/$defs/Line"}}
                  },
                  "$defs": {
                    "Customer": {"type": "object", "properties": {"name": {"type": "string"}}},
                    "Line": {"type": "object", "properties": {"label": {"type": "string"}}}
                  }
                }"""),
                GSonUtils.normalize(GlobJsonSchema.toSchemaJson(Basket.TYPE)));
    }

    /** The reason nested types are referenced rather than inlined: inlining would not terminate. */
    @Test
    public void recursiveTypeTerminates() {
        assertEquals(GSonUtils.normalize("""
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string"},
                    "children": {"type": "array", "items": {"$ref": "#/$defs/Node"}}
                  },
                  "$defs": {
                    "Node": {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string"},
                        "children": {"type": "array", "items": {"$ref": "#/$defs/Node"}}
                      }
                    }
                  }
                }"""),
                GSonUtils.normalize(GlobJsonSchema.toSchemaJson(Node.TYPE)));
    }

    /** On an array field, `enum` and `maxLength` constrain each item, not the array itself. */
    @Test
    public void valueConstraintsOnAnArrayApplyToItsItems() {
        GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Selection");
        typeBuilder.declareStringArrayField("columns",
                EnumAnnotation.create(new String[]{"a", "b"}),
                MaxSize.create(4),
                Comment.TYPE.instantiate().set(Comment.VALUE, "Columns to return"));

        assertEquals(GSonUtils.normalize("""
                {
                  "type": "object",
                  "properties": {
                    "columns": {
                      "type": "array",
                      "description": "Columns to return",
                      "items": {"type": "string", "enum": ["a", "b"], "maxLength": 4}
                    }
                  }
                }"""),
                GSonUtils.normalize(GlobJsonSchema.toSchemaJson(typeBuilder.build())));
    }

    /** A type name that is not a valid JSON pointer token is sanitized before being used as a $defs key. */
    @Test
    public void typeNameIsSanitized() {
        GlobTypeBuilder targetBuilder = GlobTypeBuilderFactory.create("com.acme/Weird Type");
        targetBuilder.declareStringField("value");
        GlobType target = targetBuilder.build();

        GlobTypeBuilder holderBuilder = GlobTypeBuilderFactory.create("Holder");
        holderBuilder.declareGlobField("inner", () -> target);

        assertEquals(GSonUtils.normalize("""
                {
                  "type": "object",
                  "properties": {"inner": {"$ref": "#/$defs/com_acme_Weird_Type"}},
                  "$defs": {
                    "com_acme_Weird_Type": {"type": "object", "properties": {"value": {"type": "string"}}}
                  }
                }"""),
                GSonUtils.normalize(GlobJsonSchema.toSchemaJson(holderBuilder.build())));
    }

    public static class Order {
        public static final GlobType TYPE;
        public static final StringField reference;
        public static final IntegerField quantity;
        public static final DoubleField price;
        public static final StringField currency;
        public static final BooleanField urgent;
        public static final DateField wantedFor;
        public static final StringArrayField tags;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Order");
            reference = typeBuilder.declareStringField("reference", Required.UNIQUE_GLOB,
                    Comment.TYPE.instantiate().set(Comment.VALUE, "The order reference"),
                    MaxSize.create(12));
            quantity = typeBuilder.declareIntegerField("quantity", Required.UNIQUE_GLOB);
            price = typeBuilder.declareDoubleField("price");
            currency = typeBuilder.declareStringField("currency", EnumAnnotation.create(new String[]{"EUR", "USD"}));
            urgent = typeBuilder.declareBooleanField("urgent");
            wantedFor = typeBuilder.declareDateField("wantedFor");
            tags = typeBuilder.declareStringArrayField("tags");
            typeBuilder.addAnnotation(Comment.TYPE.instantiate().set(Comment.VALUE, "An order to place"));
            TYPE = typeBuilder.build();
        }
    }

    public static class Customer {
        public static final GlobType TYPE;
        public static final StringField name;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Customer");
            name = typeBuilder.declareStringField("name");
            TYPE = typeBuilder.build();
        }
    }

    public static class Line {
        public static final GlobType TYPE;
        public static final StringField label;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Line");
            label = typeBuilder.declareStringField("label");
            TYPE = typeBuilder.build();
        }
    }

    public static class Basket {
        public static final GlobType TYPE;
        public static final GlobField<Customer> customer;
        public static final GlobArrayField<Line> lines;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Basket");
            customer = typeBuilder.declareGlobField("customer", () -> Customer.TYPE);
            lines = typeBuilder.declareGlobArrayField("lines", () -> Line.TYPE);
            TYPE = typeBuilder.build();
        }
    }

    public static class Node {
        public static final GlobType TYPE;
        public static final StringField name;
        public static final GlobArrayField<Node> children;

        static {
            GlobTypeBuilder typeBuilder = GlobTypeBuilderFactory.create("Node");
            name = typeBuilder.declareStringField("name");
            children = typeBuilder.declareGlobArrayField("children", () -> Node.TYPE);
            TYPE = typeBuilder.build();
        }
    }
}
