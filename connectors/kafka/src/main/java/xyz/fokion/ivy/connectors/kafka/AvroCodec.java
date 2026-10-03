package xyz.fokion.ivy.connectors.kafka;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;

import xyz.fokion.ivy.spi.ConnectorException;

/**
 * Avro values in the schema registry wire format: a zero magic byte, the schema id on four bytes,
 * then the binary Avro datum. Values are written and read as Avro JSON.
 */
final class AvroCodec {

    private AvroCodec() {
    }

    static byte[] encode(String json, String schemaText, int schemaId) {
        Schema schema = new Schema.Parser().parse(schemaText);
        try {
            Object datum = new GenericDatumReader<>(schema).read(null, DecoderFactory.get().jsonDecoder(schema, json));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0);
            out.write(ByteBuffer.allocate(4).putInt(schemaId).array());
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<>(schema).write(datum, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new ConnectorException("can't convert value to Avro with the schema: " + e.getMessage(), e);
        }
    }

    /** The schema id of an encoded value. */
    static int schemaId(byte[] value) {
        if (value == null || value.length < 5 || value[0] != 0) {
            throw new ConnectorException("the value is not in the schema registry Avro format");
        }
        return ByteBuffer.wrap(value, 1, 4).getInt();
    }

    static String decode(byte[] value, String schemaText) {
        Schema schema = new Schema.Parser().parse(schemaText);
        try {
            Object datum = new GenericDatumReader<>(schema)
                    .read(null, DecoderFactory.get().binaryDecoder(value, 5, value.length - 5, null));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonEncoder encoder = EncoderFactory.get().jsonEncoder(schema, out);
            new GenericDatumWriter<>(schema).write(datum, encoder);
            encoder.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new ConnectorException("can't get value from Avro message: " + e.getMessage(), e);
        }
    }
}
