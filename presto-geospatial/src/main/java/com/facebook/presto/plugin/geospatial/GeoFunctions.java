/*
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
package com.facebook.presto.plugin.geospatial;

import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.GeometryCursor;
import com.esri.core.geometry.ListeningGeometryCursor;
import com.esri.core.geometry.MultiPath;
import com.esri.core.geometry.MultiPoint;
import com.esri.core.geometry.MultiVertexGeometry;
import com.esri.core.geometry.NonSimpleResult;
import com.esri.core.geometry.NonSimpleResult.Reason;
import com.esri.core.geometry.OperatorSimplifyOGC;
import com.esri.core.geometry.OperatorUnion;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.Polygon;
import com.esri.core.geometry.Polyline;
import com.esri.core.geometry.ogc.OGCConcreteGeometryCollection;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.core.geometry.ogc.OGCGeometryCollection;
import com.esri.core.geometry.ogc.OGCLineString;
import com.esri.core.geometry.ogc.OGCPoint;
import com.esri.core.geometry.ogc.OGCPolygon;
import com.facebook.presto.geospatial.GeometryType;
import com.facebook.presto.geospatial.KdbTree;
import com.facebook.presto.geospatial.Rectangle;
import com.facebook.presto.geospatial.serde.GeometrySerde;
import com.facebook.presto.geospatial.serde.GeometrySerializationType;
import com.facebook.presto.geospatial.serde.JtsGeometrySerde;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.Description;
import com.facebook.presto.spi.function.ScalarFunction;
import com.facebook.presto.spi.function.SqlNullable;
import com.facebook.presto.spi.function.SqlType;
import com.facebook.presto.spi.type.IntegerType;
import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import static com.esri.core.geometry.Geometry.Type;
import static com.esri.core.geometry.NonSimpleResult.Reason.Clustering;
import static com.esri.core.geometry.NonSimpleResult.Reason.Cracking;
import static com.esri.core.geometry.NonSimpleResult.Reason.CrossOver;
import static com.esri.core.geometry.NonSimpleResult.Reason.DegenerateSegments;
import static com.esri.core.geometry.NonSimpleResult.Reason.OGCDisconnectedInterior;
import static com.esri.core.geometry.NonSimpleResult.Reason.OGCPolygonSelfTangency;
import static com.esri.core.geometry.NonSimpleResult.Reason.OGCPolylineSelfTangency;
import static com.esri.core.geometry.ogc.OGCGeometry.createFromEsriGeometry;
import static com.facebook.presto.geospatial.GeometryType.LINE_STRING;
import static com.facebook.presto.geospatial.GeometryType.MULTI_LINE_STRING;
import static com.facebook.presto.geospatial.GeometryType.MULTI_POINT;
import static com.facebook.presto.geospatial.GeometryType.MULTI_POLYGON;
import static com.facebook.presto.geospatial.GeometryType.POINT;
import static com.facebook.presto.geospatial.GeometryType.POLYGON;
import static com.facebook.presto.geospatial.GeometryUtils.getPointCount;
import static com.facebook.presto.geospatial.GeometryUtils.makeJtsEmptyPoint;
import static com.facebook.presto.geospatial.GeometryUtils.makeJtsPoint;
import static com.facebook.presto.geospatial.serde.GeometrySerde.deserialize;
import static com.facebook.presto.geospatial.serde.GeometrySerde.deserializeEnvelope;
import static com.facebook.presto.geospatial.serde.GeometrySerde.deserializeType;
import static com.facebook.presto.geospatial.serde.GeometrySerde.serialize;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY_TYPE_NAME;
import static com.facebook.presto.plugin.geospatial.SphericalGeographyType.SPHERICAL_GEOGRAPHY_TYPE_NAME;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.type.StandardTypes.BIGINT;
import static com.facebook.presto.spi.type.StandardTypes.BOOLEAN;
import static com.facebook.presto.spi.type.StandardTypes.DOUBLE;
import static com.facebook.presto.spi.type.StandardTypes.INTEGER;
import static com.facebook.presto.spi.type.StandardTypes.TINYINT;
import static com.facebook.presto.spi.type.StandardTypes.VARBINARY;
import static com.facebook.presto.spi.type.StandardTypes.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Double.isInfinite;
import static java.lang.Double.isNaN;
import static java.lang.Math.PI;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;
import static java.lang.Math.toIntExact;
import static java.lang.Math.toRadians;
import static java.lang.String.format;
import static java.util.Arrays.setAll;
import static java.util.Objects.requireNonNull;
import static org.locationtech.jts.simplify.TopologyPreservingSimplifier.simplify;

public final class GeoFunctions
{
    private static final Joiner OR_JOINER = Joiner.on(" or ");
    private static final Slice EMPTY_POLYGON = serialize(new OGCPolygon(new Polygon(), null));
    private static final Slice EMPTY_MULTIPOINT = serialize(createFromEsriGeometry(new MultiPoint(), null, true));
    private static final double EARTH_RADIUS_KM = 6371.01;
    private static final double EARTH_RADIUS_M = EARTH_RADIUS_KM * 1000.0;
    private static final Map<Reason, String> NON_SIMPLE_REASONS = ImmutableMap.<Reason, String>builder()
            .put(DegenerateSegments, "Degenerate segments")
            .put(Clustering, "Repeated points")
            .put(Cracking, "Intersecting or overlapping segments")
            .put(CrossOver, "Self-intersection")
            .put(OGCPolylineSelfTangency, "Self-tangency")
            .put(OGCPolygonSelfTangency, "Self-tangency")
            .put(OGCDisconnectedInterior, "Disconnected interior")
            .build();
    private static final int NUMBER_OF_DIMENSIONS = 3;
    private static final Block EMPTY_ARRAY_OF_INTS = IntegerType.INTEGER.createFixedSizeBlockBuilder(0).build();

    private static final float MIN_LATITUDE = -90;
    private static final float MAX_LATITUDE = 90;
    private static final float MIN_LONGITUDE = -180;
    private static final float MAX_LONGITUDE = 180;

    private static final EnumSet<Type> GEOMETRY_TYPES_FOR_SPHERICAL_GEOGRAPHY = EnumSet.of(
            Type.Point, Type.Polyline, Type.Polygon, Type.MultiPoint);

    private GeoFunctions() {}

    @Description("Returns a Geometry type LineString object from Well-Known Text representation (WKT)")
    @ScalarFunction("ST_LineFromText")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice parseLine(@SqlType(VARCHAR) Slice input)
    {
        OGCGeometry geometry = geometryFromText(input);
        validateType("ST_LineFromText", geometry, EnumSet.of(LINE_STRING));
        return serialize(geometry);
    }

    @Description("Returns a LineString from an array of points")
    @ScalarFunction("ST_LineString")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stLineString(@SqlType("array(" + GEOMETRY_TYPE_NAME + ")") Block input)
    {
        MultiPath multipath = new Polyline();
        OGCPoint previousPoint = null;
        for (int i = 0; i < input.getPositionCount(); i++) {
            Slice slice = GEOMETRY.getSlice(input, i);

            if (slice.getInput().available() == 0) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Invalid input to ST_LineString: null point at index %s", i + 1));
            }

            OGCGeometry geometry = deserialize(slice);
            if (!(geometry instanceof OGCPoint)) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("ST_LineString takes only an array of valid points, %s was passed", geometry.geometryType()));
            }
            OGCPoint point = (OGCPoint) geometry;

            if (point.isEmpty()) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Invalid input to ST_LineString: empty point at index %s", i + 1));
            }

            if (previousPoint == null) {
                multipath.startPath(point.X(), point.Y());
            }
            else {
                if (point.Equals(previousPoint)) {
                    throw new PrestoException(INVALID_FUNCTION_ARGUMENT,
                            format("Invalid input to ST_LineString: consecutive duplicate points at index %s", i + 1));
                }
                multipath.lineTo(point.X(), point.Y());
            }
            previousPoint = point;
        }
        OGCLineString linestring = new OGCLineString(multipath, 0, null);
        return serialize(linestring);
    }

    @Description("Returns a Geometry type Point object with the given coordinate values")
    @ScalarFunction("ST_Point")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stPoint(@SqlType(DOUBLE) double x, @SqlType(DOUBLE) double y)
    {
        OGCGeometry geometry = createFromEsriGeometry(new Point(x, y), null);
        return serialize(geometry);
    }

    @SqlNullable
    @Description("Returns a multi-point geometry formed from input points")
    @ScalarFunction("ST_MultiPoint")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stMultiPoint(@SqlType("array(" + GEOMETRY_TYPE_NAME + ")") Block input)
    {
        MultiPoint multipoint = new MultiPoint();
        for (int i = 0; i < input.getPositionCount(); i++) {
            if (input.isNull(i)) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Invalid input to ST_MultiPoint: null at index %s", i + 1));
            }

            Slice slice = GEOMETRY.getSlice(input, i);
            OGCGeometry geometry = deserialize(slice);
            if (!(geometry instanceof OGCPoint)) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Invalid input to ST_MultiPoint: geometry is not a point: %s at index %s", geometry.geometryType(), i + 1));
            }
            OGCPoint point = (OGCPoint) geometry;
            if (point.isEmpty()) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Invalid input to ST_MultiPoint: empty point at index %s", i + 1));
            }

            multipoint.add(point.X(), point.Y());
        }
        if (multipoint.getPointCount() == 0) {
            return null;
        }
        return serialize(createFromEsriGeometry(multipoint, null, true));
    }

    @Description("Returns a Geometry type Polygon object from Well-Known Text representation (WKT)")
    @ScalarFunction("ST_Polygon")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stPolygon(@SqlType(VARCHAR) Slice input)
    {
        OGCGeometry geometry = geometryFromText(input);
        validateType("ST_Polygon", geometry, EnumSet.of(POLYGON));
        return serialize(geometry);
    }

    @Description("Returns the 2D Euclidean area of a geometry")
    @ScalarFunction("ST_Area")
    @SqlType(DOUBLE)
    public static double stArea(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);

        // The Esri geometry library does not support area for geometry collections. We compute the area
        // of collections by summing the area of the individual components.
        GeometryType type = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (type == GeometryType.GEOMETRY_COLLECTION) {
            double area = 0.0;
            GeometryCursor cursor = geometry.getEsriGeometryCursor();
            while (true) {
                com.esri.core.geometry.Geometry esriGeometry = cursor.next();
                if (esriGeometry == null) {
                    return area;
                }

                area += esriGeometry.calculateArea2D();
            }
        }
        return geometry.getEsriGeometry().calculateArea2D();
    }

    @Description("Returns a Geometry type object from Well-Known Text representation (WKT)")
    @ScalarFunction("ST_GeometryFromText")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stGeometryFromText(@SqlType(VARCHAR) Slice input)
    {
        return serialize(geometryFromText(input));
    }

    @Description("Returns a Geometry type object from Well-Known Binary representation (WKB)")
    @ScalarFunction("ST_GeomFromBinary")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stGeomFromBinary(@SqlType(VARBINARY) Slice input)
    {
        return serialize(geomFromBinary(input));
    }

    @Description("Converts a Geometry object to a SphericalGeography object")
    @ScalarFunction("to_spherical_geography")
    @SqlType(SPHERICAL_GEOGRAPHY_TYPE_NAME)
    public static Slice toSphericalGeography(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        // "every point in input is in range" <=> "the envelope of input is in range"
        Envelope envelope = deserializeEnvelope(input);
        if (!envelope.isEmpty()) {
            checkLatitude(envelope.getYMin());
            checkLatitude(envelope.getYMax());
            checkLongitude(envelope.getXMin());
            checkLongitude(envelope.getXMax());
        }
        OGCGeometry geometry = deserialize(input);
        if (geometry.is3D()) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Cannot convert 3D geometry to a spherical geography");
        }

        GeometryCursor cursor = geometry.getEsriGeometryCursor();
        while (true) {
            com.esri.core.geometry.Geometry subGeometry = cursor.next();
            if (subGeometry == null) {
                break;
            }

            if (!GEOMETRY_TYPES_FOR_SPHERICAL_GEOGRAPHY.contains(subGeometry.getType())) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Cannot convert geometry of this type to spherical geography: " + subGeometry.getType());
            }
        }

        return input;
    }

    @Description("Converts a SphericalGeography object to a Geometry object.")
    @ScalarFunction("to_geometry")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice toGeometry(@SqlType(SPHERICAL_GEOGRAPHY_TYPE_NAME) Slice input)
    {
        // Every SphericalGeography object is a valid geometry object
        return input;
    }

    @Description("Returns the Well-Known Text (WKT) representation of the geometry")
    @ScalarFunction("ST_AsText")
    @SqlType(VARCHAR)
    public static Slice stAsText(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return utf8Slice(deserialize(input).asText());
    }

    @Description("Returns the Well-Known Binary (WKB) representation of the geometry")
    @ScalarFunction("ST_AsBinary")
    @SqlType(VARBINARY)
    public static Slice stAsBinary(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return wrappedBuffer(deserialize(input).asBinary());
    }

    @SqlNullable
    @Description("Returns the geometry that represents all points whose distance from the specified geometry is less than or equal to the specified distance")
    @ScalarFunction("ST_Buffer")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stBuffer(@SqlType(GEOMETRY_TYPE_NAME) Slice input, @SqlType(DOUBLE) double distance)
    {
        if (isNaN(distance)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distance is NaN");
        }

        if (distance < 0) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distance is negative");
        }

        if (distance == 0) {
            return input;
        }

        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return null;
        }
        return serialize(geometry.buffer(distance));
    }

    @Description("Returns the Point value that is the mathematical centroid of a Geometry")
    @ScalarFunction("ST_Centroid")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stCentroid(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_Centroid", geometry, EnumSet.of(POINT, MULTI_POINT, LINE_STRING, MULTI_LINE_STRING, POLYGON, MULTI_POLYGON));
        GeometryType geometryType = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (geometryType == GeometryType.POINT) {
            return input;
        }

        int pointCount = ((MultiVertexGeometry) geometry.getEsriGeometry()).getPointCount();
        if (pointCount == 0) {
            return serialize(createFromEsriGeometry(new Point(), geometry.getEsriSpatialReference()));
        }

        return serialize(geometry.centroid());
    }

    @Description("Returns the minimum convex geometry that encloses all input geometries")
    @ScalarFunction("ST_ConvexHull")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stConvexHull(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return input;
        }
        if (GeometryType.getForEsriGeometryType(geometry.geometryType()) == POINT) {
            return input;
        }
        return serialize(geometry.convexHull());
    }

    @Description("Return the coordinate dimension of the Geometry")
    @ScalarFunction("ST_CoordDim")
    @SqlType(TINYINT)
    public static long stCoordinateDimension(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return deserialize(input).coordinateDimension();
    }

    @Description("Returns the inherent dimension of this Geometry object, which must be less than or equal to the coordinate dimension")
    @ScalarFunction("ST_Dimension")
    @SqlType(TINYINT)
    public static long stDimension(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return deserialize(input).dimension();
    }

    @SqlNullable
    @Description("Returns TRUE if the LineString or Multi-LineString's start and end points are coincident")
    @ScalarFunction("ST_IsClosed")
    @SqlType(BOOLEAN)
    public static Boolean stIsClosed(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_IsClosed", geometry, EnumSet.of(LINE_STRING, MULTI_LINE_STRING));
        MultiPath lines = (MultiPath) geometry.getEsriGeometry();
        int pathCount = lines.getPathCount();
        for (int i = 0; i < pathCount; i++) {
            Point start = lines.getPoint(lines.getPathStart(i));
            Point end = lines.getPoint(lines.getPathEnd(i) - 1);
            if (!end.equals(start)) {
                return false;
            }
        }
        return true;
    }

    @SqlNullable
    @Description("Returns TRUE if this Geometry is an empty geometrycollection, polygon, point etc")
    @ScalarFunction("ST_IsEmpty")
    @SqlType(BOOLEAN)
    public static Boolean stIsEmpty(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return deserializeEnvelope(input).isEmpty();
    }

    @Description("Returns TRUE if this Geometry has no anomalous geometric points, such as self intersection or self tangency")
    @ScalarFunction("ST_IsSimple")
    @SqlType(BOOLEAN)
    public static boolean stIsSimple(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        return geometry.isEmpty() || geometry.isSimple();
    }

    @Description("Returns true if the input geometry is well formed")
    @ScalarFunction("ST_IsValid")
    @SqlType(BOOLEAN)
    public static boolean stIsValid(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        GeometryCursor cursor = deserialize(input).getEsriGeometryCursor();
        while (true) {
            com.esri.core.geometry.Geometry geometry = cursor.next();
            if (geometry == null) {
                return true;
            }

            if (!OperatorSimplifyOGC.local().isSimpleOGC(geometry, null, true, null, null)) {
                return false;
            }
        }
    }

    @Description("Returns the reason for why the input geometry is not valid. Returns null if the input is valid.")
    @ScalarFunction("geometry_invalid_reason")
    @SqlType(VARCHAR)
    @SqlNullable
    public static Slice invalidReason(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        GeometryCursor cursor = deserialize(input).getEsriGeometryCursor();
        NonSimpleResult result = new NonSimpleResult();
        while (true) {
            com.esri.core.geometry.Geometry geometry = cursor.next();
            if (geometry == null) {
                return null;
            }

            if (!OperatorSimplifyOGC.local().isSimpleOGC(geometry, null, true, result, null)) {
                String reasonText = NON_SIMPLE_REASONS.getOrDefault(result.m_reason, result.m_reason.name());

                if (!(geometry instanceof MultiVertexGeometry)) {
                    return utf8Slice(reasonText);
                }

                MultiVertexGeometry multiVertexGeometry = (MultiVertexGeometry) geometry;
                if (result.m_vertexIndex1 >= 0 && result.m_vertexIndex2 >= 0) {
                    Point point1 = multiVertexGeometry.getPoint(result.m_vertexIndex1);
                    Point point2 = multiVertexGeometry.getPoint(result.m_vertexIndex2);
                    return utf8Slice(format("%s at or near (%s %s) and (%s %s)", reasonText, point1.getX(), point1.getY(), point2.getX(), point2.getY()));
                }

                if (result.m_vertexIndex1 >= 0) {
                    Point point = multiVertexGeometry.getPoint(result.m_vertexIndex1);
                    return utf8Slice(format("%s at or near (%s %s)", reasonText, point.getX(), point.getY()));
                }

                return utf8Slice(reasonText);
            }
        }
    }

    @Description("Returns the length of a LineString or Multi-LineString using Euclidean measurement on a 2D plane (based on spatial ref) in projected units")
    @ScalarFunction("ST_Length")
    @SqlType(DOUBLE)
    public static double stLength(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_Length", geometry, EnumSet.of(LINE_STRING, MULTI_LINE_STRING));
        return geometry.getEsriGeometry().calculateLength2D();
    }

    @SqlNullable
    @Description("Returns a float between 0 and 1 representing the location of the closest point on the LineString to the given Point, as a fraction of total 2d line length.")
    @ScalarFunction("line_locate_point")
    @SqlType(DOUBLE)
    public static Double lineLocatePoint(@SqlType(GEOMETRY_TYPE_NAME) Slice lineSlice, @SqlType(GEOMETRY_TYPE_NAME) Slice pointSlice)
    {
        Geometry line = JtsGeometrySerde.deserialize(lineSlice);
        Geometry point = JtsGeometrySerde.deserialize(pointSlice);

        if (line.isEmpty() || point.isEmpty()) {
            return null;
        }

        GeometryType lineType = GeometryType.getForJtsGeometryType(line.getGeometryType());
        if (lineType != GeometryType.LINE_STRING && lineType != GeometryType.MULTI_LINE_STRING) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("First argument to line_locate_point must be a LineString or a MultiLineString. Got: %s", line.getGeometryType()));
        }

        GeometryType pointType = GeometryType.getForJtsGeometryType(point.getGeometryType());
        if (pointType != GeometryType.POINT) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Second argument to line_locate_point must be a Point. Got: %s", point.getGeometryType()));
        }

        return new LengthIndexedLine(line).indexOf(point.getCoordinate()) / line.getLength();
    }

    @Description("Returns the point in the line at the fractional length.")
    @ScalarFunction("line_interpolate_point")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice lineInterpolatePoint(@SqlType(GEOMETRY_TYPE_NAME) Slice lineSlice, @SqlType(DOUBLE) double fraction)
    {
        if (!(0.0 <= fraction && fraction <= 1.0)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("line_interpolate_point: Fraction must be between 0 and 1, but is %s", fraction));
        }

        Geometry geometry = JtsGeometrySerde.deserialize(lineSlice);
        validateType("line_interpolate_point", geometry, ImmutableSet.of(LINE_STRING));
        LineString line = (LineString) geometry;

        if (line.isEmpty()) {
            return JtsGeometrySerde.serialize(makeJtsEmptyPoint());
        }

        org.locationtech.jts.geom.Coordinate coordinate = new LengthIndexedLine(line).extractPoint(fraction * line.getLength());
        return JtsGeometrySerde.serialize(makeJtsPoint(coordinate));
    }

    @SqlNullable
    @Description("Returns X maxima of a bounding box of a Geometry")
    @ScalarFunction("ST_XMax")
    @SqlType(DOUBLE)
    public static Double stXMax(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return null;
        }
        return envelope.getXMax();
    }

    @SqlNullable
    @Description("Returns Y maxima of a bounding box of a Geometry")
    @ScalarFunction("ST_YMax")
    @SqlType(DOUBLE)
    public static Double stYMax(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return null;
        }
        return envelope.getYMax();
    }

    @SqlNullable
    @Description("Returns X minima of a bounding box of a Geometry")
    @ScalarFunction("ST_XMin")
    @SqlType(DOUBLE)
    public static Double stXMin(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return null;
        }
        return envelope.getXMin();
    }

    @SqlNullable
    @Description("Returns Y minima of a bounding box of a Geometry")
    @ScalarFunction("ST_YMin")
    @SqlType(DOUBLE)
    public static Double stYMin(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return null;
        }
        return envelope.getYMin();
    }

    @SqlNullable
    @Description("Returns the cardinality of the collection of interior rings of a polygon")
    @ScalarFunction("ST_NumInteriorRing")
    @SqlType(BIGINT)
    public static Long stNumInteriorRings(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_NumInteriorRing", geometry, EnumSet.of(POLYGON));
        if (geometry.isEmpty()) {
            return null;
        }
        return Long.valueOf(((OGCPolygon) geometry).numInteriorRing());
    }

    @SqlNullable
    @Description("Returns an array of interior rings of a polygon")
    @ScalarFunction("ST_InteriorRings")
    @SqlType("array(" + GEOMETRY_TYPE_NAME + ")")
    public static Block stInteriorRings(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_InteriorRings", geometry, EnumSet.of(POLYGON));
        if (geometry.isEmpty()) {
            return null;
        }

        OGCPolygon polygon = (OGCPolygon) geometry;
        BlockBuilder blockBuilder = GEOMETRY.createBlockBuilder(null, polygon.numInteriorRing());
        for (int i = 0; i < polygon.numInteriorRing(); i++) {
            GEOMETRY.writeSlice(blockBuilder, serialize(polygon.interiorRingN(i)));
        }
        return blockBuilder.build();
    }

    @Description("Returns the cardinality of the geometry collection")
    @ScalarFunction("ST_NumGeometries")
    @SqlType(INTEGER)
    public static long stNumGeometries(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return 0;
        }
        GeometryType type = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (!type.isMultitype()) {
            return 1;
        }
        return ((OGCGeometryCollection) geometry).numGeometries();
    }

    @Description("Returns a geometry that represents the point set union of the input geometries.")
    @ScalarFunction("ST_Union")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stUnion(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        return stUnion(ImmutableList.of(left, right));
    }

    @Description("Returns a geometry that represents the point set union of the input geometries.")
    @ScalarFunction("geometry_union")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice geometryUnion(@SqlType("array(" + GEOMETRY_TYPE_NAME + ")") Block input)
    {
        return stUnion(getGeometrySlicesFromBlock(input));
    }

    private static Slice stUnion(Iterable<Slice> slices)
    {
        // The current state of Esri/geometry-api-java does not allow support for multiple dimensions being
        // fed to the union operator without dropping the lower dimensions:
        // https://github.com/Esri/geometry-api-java/issues/199
        // When operating over a collection of geometries, it is more efficient to reuse the same operator
        // for the entire operation.  Therefore, split the inputs and operators by dimension, and then union
        // each dimension's result at the end.
        ListeningGeometryCursor[] cursorsByDimension = new ListeningGeometryCursor[NUMBER_OF_DIMENSIONS];
        GeometryCursor[] operatorsByDimension = new GeometryCursor[NUMBER_OF_DIMENSIONS];

        setAll(cursorsByDimension, i -> new ListeningGeometryCursor());
        setAll(operatorsByDimension, i -> OperatorUnion.local().execute(cursorsByDimension[i], null, null));

        Iterator<Slice> slicesIterator = slices.iterator();
        if (!slicesIterator.hasNext()) {
            return null;
        }
        while (slicesIterator.hasNext()) {
            Slice slice = slicesIterator.next();
            // Ignore null inputs
            if (slice.getInput().available() == 0) {
                continue;
            }

            for (OGCGeometry geometry : flattenCollection(deserialize(slice))) {
                int dimension = geometry.dimension();
                cursorsByDimension[dimension].tick(geometry.getEsriGeometry());
                operatorsByDimension[dimension].tock();
            }
        }

        List<OGCGeometry> outputs = new ArrayList<>();
        for (GeometryCursor operator : operatorsByDimension) {
            OGCGeometry unionedGeometry = createFromEsriGeometry(operator.next(), null);
            if (unionedGeometry != null) {
                outputs.add(unionedGeometry);
            }
        }

        if (outputs.size() == 1) {
            return serialize(outputs.get(0));
        }
        return serialize(new OGCConcreteGeometryCollection(outputs, null).flattenAndRemoveOverlaps().reduceFromMulti());
    }

    @SqlNullable
    @Description("Returns the geometry element at the specified index (indices started with 1)")
    @ScalarFunction("ST_GeometryN")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stGeometryN(@SqlType(GEOMETRY_TYPE_NAME) Slice input, @SqlType(INTEGER) long index)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return null;
        }
        GeometryType type = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (!type.isMultitype()) {
            if (index == 1) {
                return input;
            }
            return null;
        }
        OGCGeometryCollection geometryCollection = ((OGCGeometryCollection) geometry);
        if (index < 1 || index > geometryCollection.numGeometries()) {
            return null;
        }
        OGCGeometry ogcGeometry = geometryCollection.geometryN((int) index - 1);
        return serialize(ogcGeometry);
    }

    @SqlNullable
    @Description("Returns the vertex of a linestring at the specified index (indices started with 1) ")
    @ScalarFunction("ST_PointN")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stPointN(@SqlType(GEOMETRY_TYPE_NAME) Slice input, @SqlType(INTEGER) long index)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_PointN", geometry, EnumSet.of(LINE_STRING));

        OGCLineString linestring = (OGCLineString) geometry;
        if (index < 1 || index > linestring.numPoints()) {
            return null;
        }
        return serialize(linestring.pointN(toIntExact(index) - 1));
    }

    @SqlNullable
    @Description("Returns an array of geometries in the specified collection")
    @ScalarFunction("ST_Geometries")
    @SqlType("array(" + GEOMETRY_TYPE_NAME + ")")
    public static Block stGeometries(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return null;
        }

        GeometryType type = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (!type.isMultitype()) {
            BlockBuilder blockBuilder = GEOMETRY.createBlockBuilder(null, 1);
            GEOMETRY.writeSlice(blockBuilder, serialize(geometry));
            return blockBuilder.build();
        }

        OGCGeometryCollection collection = (OGCGeometryCollection) geometry;
        BlockBuilder blockBuilder = GEOMETRY.createBlockBuilder(null, collection.numGeometries());
        for (int i = 0; i < collection.numGeometries(); i++) {
            GEOMETRY.writeSlice(blockBuilder, serialize(collection.geometryN(i)));
        }
        return blockBuilder.build();
    }

    @SqlNullable
    @Description("Returns the interior ring element at the specified index (indices start at 1)")
    @ScalarFunction("ST_InteriorRingN")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stInteriorRingN(@SqlType(GEOMETRY_TYPE_NAME) Slice input, @SqlType(INTEGER) long index)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_InteriorRingN", geometry, EnumSet.of(POLYGON));
        OGCPolygon polygon = (OGCPolygon) geometry;
        if (index < 1 || index > polygon.numInteriorRing()) {
            return null;
        }
        OGCGeometry interiorRing = polygon.interiorRingN(toIntExact(index) - 1);
        return serialize(interiorRing);
    }

    @Description("Returns the number of points in a Geometry")
    @ScalarFunction("ST_NumPoints")
    @SqlType(BIGINT)
    public static long stNumPoints(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return getPointCount(deserialize(input));
    }

    @SqlNullable
    @Description("Returns TRUE if and only if the line is closed and simple")
    @ScalarFunction("ST_IsRing")
    @SqlType(BOOLEAN)
    public static Boolean stIsRing(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_IsRing", geometry, EnumSet.of(LINE_STRING));
        OGCLineString line = (OGCLineString) geometry;
        return line.isClosed() && line.isSimple();
    }

    @SqlNullable
    @Description("Returns the first point of a LINESTRING geometry as a Point")
    @ScalarFunction("ST_StartPoint")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stStartPoint(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_StartPoint", geometry, EnumSet.of(LINE_STRING));
        if (geometry.isEmpty()) {
            return null;
        }
        MultiPath lines = (MultiPath) geometry.getEsriGeometry();
        return serialize(createFromEsriGeometry(lines.getPoint(0), null));
    }

    @Description("Returns a \"simplified\" version of the given geometry")
    @ScalarFunction("simplify_geometry")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice simplifyGeometry(@SqlType(GEOMETRY_TYPE_NAME) Slice input, @SqlType(DOUBLE) double distanceTolerance)
    {
        if (isNaN(distanceTolerance)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distanceTolerance is NaN");
        }

        if (distanceTolerance < 0) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distanceTolerance is negative");
        }

        if (distanceTolerance == 0) {
            return input;
        }

        return JtsGeometrySerde.serialize(simplify(JtsGeometrySerde.deserialize(input), distanceTolerance));
    }

    @SqlNullable
    @Description("Returns the last point of a LINESTRING geometry as a Point")
    @ScalarFunction("ST_EndPoint")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stEndPoint(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_EndPoint", geometry, EnumSet.of(LINE_STRING));
        if (geometry.isEmpty()) {
            return null;
        }
        MultiPath lines = (MultiPath) geometry.getEsriGeometry();
        return serialize(createFromEsriGeometry(lines.getPoint(lines.getPointCount() - 1), null));
    }

    @SqlNullable
    @Description("Returns an array of points in a linestring")
    @ScalarFunction("ST_Points")
    @SqlType("array(" + GEOMETRY_TYPE_NAME + ")")
    public static Block stPoints(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_Points", geometry, EnumSet.of(LINE_STRING));
        if (geometry.isEmpty()) {
            return null;
        }
        MultiPath lines = (MultiPath) geometry.getEsriGeometry();
        BlockBuilder blockBuilder = GEOMETRY.createBlockBuilder(null, lines.getPointCount());
        for (int i = 0; i < lines.getPointCount(); i++) {
            GEOMETRY.writeSlice(blockBuilder, serialize(createFromEsriGeometry(lines.getPoint(i), null)));
        }
        return blockBuilder.build();
    }

    @SqlNullable
    @Description("Return the X coordinate of the point")
    @ScalarFunction("ST_X")
    @SqlType(DOUBLE)
    public static Double stX(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_X", geometry, EnumSet.of(POINT));
        if (geometry.isEmpty()) {
            return null;
        }
        return ((OGCPoint) geometry).X();
    }

    @SqlNullable
    @Description("Return the Y coordinate of the point")
    @ScalarFunction("ST_Y")
    @SqlType(DOUBLE)
    public static Double stY(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_Y", geometry, EnumSet.of(POINT));
        if (geometry.isEmpty()) {
            return null;
        }
        return ((OGCPoint) geometry).Y();
    }

    @Description("Returns the closure of the combinatorial boundary of this Geometry")
    @ScalarFunction("ST_Boundary")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stBoundary(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty() && GeometryType.getForEsriGeometryType(geometry.geometryType()) == LINE_STRING) {
            // OCGGeometry#boundary crashes with NPE for LINESTRING EMPTY
            return EMPTY_MULTIPOINT;
        }
        return serialize(geometry.boundary());
    }

    @Description("Returns the bounding rectangular polygon of a Geometry")
    @ScalarFunction("ST_Envelope")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stEnvelope(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return EMPTY_POLYGON;
        }
        return serialize(envelope);
    }

    @SqlNullable
    @Description("Returns the lower left and upper right corners of bounding rectangular polygon of a Geometry")
    @ScalarFunction("ST_EnvelopeAsPts")
    @SqlType("array(" + GEOMETRY_TYPE_NAME + ")")
    public static Block stEnvelopeAsPts(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return null;
        }
        BlockBuilder blockBuilder = GEOMETRY.createBlockBuilder(null, 2);
        Point lowerLeftCorner = new Point(envelope.getXMin(), envelope.getYMin());
        Point upperRightCorner = new Point(envelope.getXMax(), envelope.getYMax());
        GEOMETRY.writeSlice(blockBuilder, serialize(createFromEsriGeometry(lowerLeftCorner, null, false)));
        GEOMETRY.writeSlice(blockBuilder, serialize(createFromEsriGeometry(upperRightCorner, null, false)));
        return blockBuilder.build();
    }

    @Description("Returns the bounding rectangle of a Geometry expanded by distance.")
    @ScalarFunction("expand_envelope")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice expandEnvelope(@SqlType(GEOMETRY_TYPE_NAME) Slice input, @SqlType(DOUBLE) double distance)
    {
        if (isNaN(distance)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "expand_envelope: distance is NaN");
        }

        if (distance < 0) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("expand_envelope: distance %s is negative", distance));
        }

        Envelope envelope = deserializeEnvelope(input);
        if (envelope.isEmpty()) {
            return EMPTY_POLYGON;
        }
        return serialize(new Envelope(
                envelope.getXMin() - distance,
                envelope.getYMin() - distance,
                envelope.getXMax() + distance,
                envelope.getYMax() + distance));
    }

    @Description("Returns the Geometry value that represents the point set difference of two geometries")
    @ScalarFunction("ST_Difference")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stDifference(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return serialize(leftGeometry.difference(rightGeometry));
    }

    @SqlNullable
    @Description("Returns the 2-dimensional cartesian minimum distance (based on spatial ref) between two geometries in projected units")
    @ScalarFunction("ST_Distance")
    @SqlType(DOUBLE)
    public static Double stDistance(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.isEmpty() || rightGeometry.isEmpty() ? null : leftGeometry.distance(rightGeometry);
    }

    @SqlNullable
    @Description("Returns a line string representing the exterior ring of the POLYGON")
    @ScalarFunction("ST_ExteriorRing")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stExteriorRing(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        validateType("ST_ExteriorRing", geometry, EnumSet.of(POLYGON));
        if (geometry.isEmpty()) {
            return null;
        }
        return serialize(((OGCPolygon) geometry).exteriorRing());
    }

    @Description("Returns the Geometry value that represents the point set intersection of two Geometries")
    @ScalarFunction("ST_Intersection")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stIntersection(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (deserializeType(left) == GeometrySerializationType.ENVELOPE && deserializeType(right) == GeometrySerializationType.ENVELOPE) {
            Envelope leftEnvelope = deserializeEnvelope(left);
            Envelope rightEnvelope = deserializeEnvelope(right);

            // Envelope#intersect updates leftEnvelope to the intersection of the two envelopes
            if (!leftEnvelope.intersect(rightEnvelope)) {
                return EMPTY_POLYGON;
            }

            Envelope intersection = leftEnvelope;
            if (intersection.getXMin() == intersection.getXMax()) {
                if (intersection.getYMin() == intersection.getYMax()) {
                    return serialize(createFromEsriGeometry(new Point(intersection.getXMin(), intersection.getXMax()), null));
                }
                return serialize(createFromEsriGeometry(new Polyline(new Point(intersection.getXMin(), intersection.getYMin()), new Point(intersection.getXMin(), intersection.getYMax())), null));
            }

            if (intersection.getYMin() == intersection.getYMax()) {
                return serialize(createFromEsriGeometry(new Polyline(new Point(intersection.getXMin(), intersection.getYMin()), new Point(intersection.getXMax(), intersection.getYMin())), null));
            }

            return serialize(intersection);
        }

        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return serialize(leftGeometry.intersection(rightGeometry));
    }

    @Description("Returns the Geometry value that represents the point set symmetric difference of two Geometries")
    @ScalarFunction("ST_SymDifference")
    @SqlType(GEOMETRY_TYPE_NAME)
    public static Slice stSymmetricDifference(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return serialize(leftGeometry.symDifference(rightGeometry));
    }

    @SqlNullable
    @Description("Returns TRUE if and only if no points of right lie in the exterior of left, and at least one point of the interior of left lies in the interior of right")
    @ScalarFunction("ST_Contains")
    @SqlType(BOOLEAN)
    public static Boolean stContains(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(left, right, Envelope::contains)) {
            return false;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.contains(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if the supplied geometries have some, but not all, interior points in common")
    @ScalarFunction("ST_Crosses")
    @SqlType(BOOLEAN)
    public static Boolean stCrosses(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(left, right, Envelope::intersect)) {
            return false;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.crosses(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if the Geometries do not spatially intersect - if they do not share any space together")
    @ScalarFunction("ST_Disjoint")
    @SqlType(BOOLEAN)
    public static Boolean stDisjoint(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(left, right, Envelope::intersect)) {
            return true;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.disjoint(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if the given geometries represent the same geometry")
    @ScalarFunction("ST_Equals")
    @SqlType(BOOLEAN)
    public static Boolean stEquals(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.equals(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if the Geometries spatially intersect in 2D - (share any portion of space) and FALSE if they don't (they are Disjoint)")
    @ScalarFunction("ST_Intersects")
    @SqlType(BOOLEAN)
    public static Boolean stIntersects(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(left, right, Envelope::intersect)) {
            return false;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.intersects(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if the Geometries share space, are of the same dimension, but are not completely contained by each other")
    @ScalarFunction("ST_Overlaps")
    @SqlType(BOOLEAN)
    public static Boolean stOverlaps(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(left, right, Envelope::intersect)) {
            return false;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.overlaps(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if this Geometry is spatially related to another Geometry")
    @ScalarFunction("ST_Relate")
    @SqlType(BOOLEAN)
    public static Boolean stRelate(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right, @SqlType(VARCHAR) Slice relation)
    {
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.relate(rightGeometry, relation.toStringUtf8());
    }

    @SqlNullable
    @Description("Returns TRUE if the geometries have at least one point in common, but their interiors do not intersect")
    @ScalarFunction("ST_Touches")
    @SqlType(BOOLEAN)
    public static Boolean stTouches(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(left, right, Envelope::intersect)) {
            return false;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.touches(rightGeometry);
    }

    @SqlNullable
    @Description("Returns TRUE if the geometry A is completely inside geometry B")
    @ScalarFunction("ST_Within")
    @SqlType(BOOLEAN)
    public static Boolean stWithin(@SqlType(GEOMETRY_TYPE_NAME) Slice left, @SqlType(GEOMETRY_TYPE_NAME) Slice right)
    {
        if (!envelopes(right, left, Envelope::contains)) {
            return false;
        }
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        verifySameSpatialReference(leftGeometry, rightGeometry);
        return leftGeometry.within(rightGeometry);
    }

    @Description("Returns the type of the geometry")
    @ScalarFunction("ST_GeometryType")
    @SqlType(VARCHAR)
    public static Slice stGeometryType(@SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        return GeometrySerde.getGeometryType(input).standardName();
    }

    @ScalarFunction
    @SqlNullable
    @Description("Returns an array of spatial partition IDs for a given geometry")
    @SqlType("array(int)")
    public static Block spatialPartitions(@SqlType(KdbTreeType.NAME) Object kdbTree, @SqlType(GEOMETRY_TYPE_NAME) Slice geometry)
    {
        Envelope envelope = deserializeEnvelope(geometry);
        if (envelope.isEmpty()) {
            // Empty geometry
            return null;
        }

        return spatialPartitions((KdbTree) kdbTree, new Rectangle(envelope.getXMin(), envelope.getYMin(), envelope.getXMax(), envelope.getYMax()));
    }

    @ScalarFunction
    @SqlNullable
    @Description("Returns an array of spatial partition IDs for a geometry representing a set of points within specified distance from the input geometry")
    @SqlType("array(int)")
    public static Block spatialPartitions(@SqlType(KdbTreeType.NAME) Object kdbTree, @SqlType(GEOMETRY_TYPE_NAME) Slice geometry, @SqlType(DOUBLE) double distance)
    {
        if (isNaN(distance)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distance is NaN");
        }

        if (isInfinite(distance)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distance is infinite");
        }

        if (distance < 0) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "distance is negative");
        }

        Envelope envelope = deserializeEnvelope(geometry);
        if (envelope.isEmpty()) {
            return null;
        }

        Rectangle expandedEnvelope2D = new Rectangle(envelope.getXMin() - distance, envelope.getYMin() - distance, envelope.getXMax() + distance, envelope.getYMax() + distance);
        return spatialPartitions((KdbTree) kdbTree, expandedEnvelope2D);
    }

    private static Block spatialPartitions(KdbTree kdbTree, Rectangle envelope)
    {
        Map<Integer, Rectangle> partitions = kdbTree.findIntersectingLeaves(envelope);
        if (partitions.isEmpty()) {
            return EMPTY_ARRAY_OF_INTS;
        }

        // For input rectangles that represent a single point, return at most one partition
        // by excluding right and upper sides of partition rectangles. The logic that builds
        // KDB tree needs to make sure to add some padding to the right and upper sides of the
        // overall extent of the tree to avoid missing right-most and top-most points.
        boolean point = (envelope.getWidth() == 0 && envelope.getHeight() == 0);
        if (point) {
            for (Map.Entry<Integer, Rectangle> partition : partitions.entrySet()) {
                if (envelope.getXMin() < partition.getValue().getXMax() && envelope.getYMin() < partition.getValue().getYMax()) {
                    BlockBuilder blockBuilder = IntegerType.INTEGER.createFixedSizeBlockBuilder(1);
                    blockBuilder.writeInt(partition.getKey());
                    return blockBuilder.build();
                }
            }
            throw new VerifyException(format("Cannot find half-open partition extent for a point: (%s, %s)", envelope.getXMin(), envelope.getYMin()));
        }

        BlockBuilder blockBuilder = IntegerType.INTEGER.createFixedSizeBlockBuilder(partitions.size());
        for (int id : partitions.keySet()) {
            blockBuilder.writeInt(id);
        }

        return blockBuilder.build();
    }

    @ScalarFunction
    @Description("Calculates the great-circle distance between two points on the Earth's surface in kilometers")
    @SqlType(DOUBLE)
    public static double greatCircleDistance(
            @SqlType(DOUBLE) double latitude1,
            @SqlType(DOUBLE) double longitude1,
            @SqlType(DOUBLE) double latitude2,
            @SqlType(DOUBLE) double longitude2)
    {
        checkLatitude(latitude1);
        checkLongitude(longitude1);
        checkLatitude(latitude2);
        checkLongitude(longitude2);

        double radianLatitude1 = toRadians(latitude1);
        double radianLatitude2 = toRadians(latitude2);

        double sin1 = sin(radianLatitude1);
        double cos1 = cos(radianLatitude1);
        double sin2 = sin(radianLatitude2);
        double cos2 = cos(radianLatitude2);

        double deltaLongitude = toRadians(longitude1) - toRadians(longitude2);
        double cosDeltaLongitude = cos(deltaLongitude);

        double t1 = cos2 * sin(deltaLongitude);
        double t2 = cos1 * sin2 - sin1 * cos2 * cosDeltaLongitude;
        double t3 = sin1 * sin2 + cos1 * cos2 * cosDeltaLongitude;
        return atan2(sqrt(t1 * t1 + t2 * t2), t3) * EARTH_RADIUS_KM;
    }

    private static void checkLatitude(double latitude)
    {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude) || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Latitude must be between -90 and 90");
        }
    }

    private static void checkLongitude(double longitude)
    {
        if (Double.isNaN(longitude) || Double.isInfinite(longitude) || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Longitude must be between -180 and 180");
        }
    }

    private static OGCGeometry geometryFromText(Slice input)
    {
        OGCGeometry geometry;
        try {
            geometry = OGCGeometry.fromText(input.toStringUtf8());
        }
        catch (IllegalArgumentException e) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Invalid WKT: " + input.toStringUtf8(), e);
        }
        geometry.setSpatialReference(null);
        return geometry;
    }

    private static OGCGeometry geomFromBinary(Slice input)
    {
        requireNonNull(input, "input is null");
        OGCGeometry geometry;
        try {
            geometry = OGCGeometry.fromBinary(input.toByteBuffer().slice());
        }
        catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Invalid WKB", e);
        }
        geometry.setSpatialReference(null);
        return geometry;
    }

    private static void validateType(String function, OGCGeometry geometry, Set<GeometryType> validTypes)
    {
        GeometryType type = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (!validTypes.contains(type)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("%s only applies to %s. Input type is: %s", function, OR_JOINER.join(validTypes), type));
        }
    }

    private static void validateType(String function, Geometry geometry, Set<GeometryType> validTypes)
    {
        GeometryType type = GeometryType.getForJtsGeometryType(geometry.getGeometryType());
        if (!validTypes.contains(type)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("%s only applies to %s. Input type is: %s", function, OR_JOINER.join(validTypes), type));
        }
    }

    private static void verifySameSpatialReference(OGCGeometry leftGeometry, OGCGeometry rightGeometry)
    {
        checkArgument(Objects.equals(leftGeometry.getEsriSpatialReference(), rightGeometry.getEsriSpatialReference()), "Input geometries must have the same spatial reference");
    }

    private static boolean envelopes(Slice left, Slice right, EnvelopesPredicate predicate)
    {
        Envelope leftEnvelope = deserializeEnvelope(left);
        Envelope rightEnvelope = deserializeEnvelope(right);
        if (leftEnvelope.isEmpty() || rightEnvelope.isEmpty()) {
            return false;
        }
        return predicate.apply(leftEnvelope, rightEnvelope);
    }

    private interface EnvelopesPredicate
    {
        boolean apply(Envelope left, Envelope right);
    }

    @SqlNullable
    @Description("Returns the great-circle distance in meters between two SphericalGeography points.")
    @ScalarFunction("ST_Distance")
    @SqlType(DOUBLE)
    public static Double stSphericalDistance(@SqlType(SPHERICAL_GEOGRAPHY_TYPE_NAME) Slice left, @SqlType(SPHERICAL_GEOGRAPHY_TYPE_NAME) Slice right)
    {
        OGCGeometry leftGeometry = deserialize(left);
        OGCGeometry rightGeometry = deserialize(right);
        if (leftGeometry.isEmpty() || rightGeometry.isEmpty()) {
            return null;
        }

        // TODO: support more SphericalGeography types.
        validateSphericalType("ST_Distance", leftGeometry, EnumSet.of(POINT));
        validateSphericalType("ST_Distance", rightGeometry, EnumSet.of(POINT));
        Point leftPoint = (Point) leftGeometry.getEsriGeometry();
        Point rightPoint = (Point) rightGeometry.getEsriGeometry();

        // greatCircleDistance returns distance in KM.
        return greatCircleDistance(leftPoint.getY(), leftPoint.getX(), rightPoint.getY(), rightPoint.getX()) * 1000;
    }

    private static void validateSphericalType(String function, OGCGeometry geometry, Set<GeometryType> validTypes)
    {
        GeometryType type = GeometryType.getForEsriGeometryType(geometry.geometryType());
        if (!validTypes.contains(type)) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("When applied to SphericalGeography inputs, %s only supports %s. Input type is: %s", function, OR_JOINER.join(validTypes), type));
        }
    }

    @SqlNullable
    @Description("Returns the area of a geometry on the Earth's surface using spherical model")
    @ScalarFunction("ST_Area")
    @SqlType(DOUBLE)
    public static Double stSphericalArea(@SqlType(SPHERICAL_GEOGRAPHY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return null;
        }

        validateSphericalType("ST_Area", geometry, EnumSet.of(POLYGON, MULTI_POLYGON));

        Polygon polygon = (Polygon) geometry.getEsriGeometry();

        // See https://www.movable-type.co.uk/scripts/latlong.html
        // and http://osgeo-org.1560.x6.nabble.com/Area-of-a-spherical-polygon-td3841625.html
        // and https://www.element84.com/blog/determining-if-a-spherical-polygon-contains-a-pole
        // for the underlying Maths

        double sphericalExcess = 0.0;

        int numPaths = polygon.getPathCount();
        for (int i = 0; i < numPaths; i++) {
            double sign = polygon.isExteriorRing(i) ? 1.0 : -1.0;
            sphericalExcess += sign * Math.abs(computeSphericalExcess(polygon, polygon.getPathStart(i), polygon.getPathEnd(i)));
        }

        // Math.abs is required here because for Polygons with a 2D area of 0
        // isExteriorRing returns false for the exterior ring
        return Math.abs(sphericalExcess * EARTH_RADIUS_M * EARTH_RADIUS_M);
    }

    @SqlNullable
    @Description("Returns the great-circle length in meters of a linestring or multi-linestring on Earth's surface")
    @ScalarFunction("ST_Length")
    @SqlType(DOUBLE)
    public static Double stSphericalLength(@SqlType(SPHERICAL_GEOGRAPHY_TYPE_NAME) Slice input)
    {
        OGCGeometry geometry = deserialize(input);
        if (geometry.isEmpty()) {
            return null;
        }

        validateSphericalType("ST_Length", geometry, EnumSet.of(LINE_STRING, MULTI_LINE_STRING));
        MultiPath lineString = (MultiPath) geometry.getEsriGeometry();

        double sum = 0;

        // sum up paths on (multi)linestring
        for (int path = 0; path < lineString.getPathCount(); path++) {
            if (lineString.getPathSize(path) < 2) {
                continue;
            }

            // sum up distances between adjacent points on this path
            int pathStart = lineString.getPathStart(path);
            Point prev = lineString.getPoint(pathStart);
            for (int i = pathStart + 1; i < lineString.getPathEnd(path); i++) {
                Point next = lineString.getPoint(i);
                sum += greatCircleDistance(prev.getY(), prev.getX(), next.getY(), next.getX());
                prev = next;
            }
        }

        return sum * 1000;
    }

    private static double computeSphericalExcess(Polygon polygon, int start, int end)
    {
        // Our calculations rely on not processing the same point twice
        if (polygon.getPoint(end - 1).equals(polygon.getPoint(start))) {
            end = end - 1;
        }

        if (end - start < 3) {
            // A path with less than 3 distinct points is not valid for calculating an area
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Polygon is not valid: a loop contains less then 3 vertices.");
        }

        Point point = new Point();

        // Initialize the calculator with the last point
        polygon.getPoint(end - 1, point);
        SphericalExcessCalculator calculator = new SphericalExcessCalculator(point);

        for (int i = start; i < end; i++) {
            polygon.getPoint(i, point);
            calculator.add(point);
        }

        return calculator.computeSphericalExcess();
    }

    private static class SphericalExcessCalculator
    {
        private static final double TWO_PI = 2 * Math.PI;
        private static final double THREE_PI = 3 * Math.PI;

        private double sphericalExcess;
        private double courseDelta;

        private boolean firstPoint;
        private double firstInitialBearing;
        private double previousFinalBearing;

        private double previousPhi;
        private double previousCos;
        private double previousSin;
        private double previousTan;
        private double previousLongitude;

        private boolean done;

        public SphericalExcessCalculator(Point endPoint)
        {
            previousPhi = toRadians(endPoint.getY());
            previousSin = Math.sin(previousPhi);
            previousCos = Math.cos(previousPhi);
            previousTan = Math.tan(previousPhi / 2);
            previousLongitude = toRadians(endPoint.getX());
            firstPoint = true;
        }

        private void add(Point point) throws IllegalStateException
        {
            checkState(!done, "Computation of spherical excess is complete");

            double phi = toRadians(point.getY());
            double tan = Math.tan(phi / 2);
            double longitude = toRadians(point.getX());

            // We need to check for that specifically
            // Otherwise calculating the bearing is not deterministic
            if (longitude == previousLongitude && phi == previousPhi) {
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Polygon is not valid: it has two identical consecutive vertices");
            }

            double deltaLongitude = longitude - previousLongitude;
            sphericalExcess += 2 * Math.atan2(Math.tan(deltaLongitude / 2) * (previousTan + tan), 1 + previousTan * tan);

            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            double sinOfDeltaLongitude = Math.sin(deltaLongitude);
            double cosOfDeltaLongitude = Math.cos(deltaLongitude);

            // Initial bearing from previous to current
            double y = sinOfDeltaLongitude * cos;
            double x = previousCos * sin - previousSin * cos * cosOfDeltaLongitude;
            double initialBearing = (Math.atan2(y, x) + TWO_PI) % TWO_PI;

            // Final bearing from previous to current = opposite of bearing from current to previous
            double finalY = -sinOfDeltaLongitude * previousCos;
            double finalX = previousSin * cos - previousCos * sin * cosOfDeltaLongitude;
            double finalBearing = (Math.atan2(finalY, finalX) + PI) % TWO_PI;

            // When processing our first point we don't yet have a previousFinalBearing
            if (firstPoint) {
                // So keep our initial bearing around, and we'll use it at the end
                // with the last final bearing
                firstInitialBearing = initialBearing;
                firstPoint = false;
            }
            else {
                courseDelta += (initialBearing - previousFinalBearing + THREE_PI) % TWO_PI - PI;
            }

            courseDelta += (finalBearing - initialBearing + THREE_PI) % TWO_PI - PI;

            previousFinalBearing = finalBearing;
            previousCos = cos;
            previousSin = sin;
            previousPhi = phi;
            previousTan = tan;
            previousLongitude = longitude;
        }

        public double computeSphericalExcess()
        {
            if (!done) {
                // Now that we have our last final bearing, we can calculate the remaining course delta
                courseDelta += (firstInitialBearing - previousFinalBearing + THREE_PI) % TWO_PI - PI;

                // The courseDelta should be 2Pi or - 2Pi, unless a pole is enclosed (and then it should be ~ 0)
                // In which case we need to correct the spherical excess by 2Pi
                if (Math.abs(courseDelta) < PI / 4) {
                    sphericalExcess = Math.abs(sphericalExcess) - TWO_PI;
                }
                done = true;
            }

            return sphericalExcess;
        }
    }

    private static Iterable<Slice> getGeometrySlicesFromBlock(Block block)
    {
        requireNonNull(block, "block is null");
        return () -> new Iterator<Slice>()
        {
            private int iteratorPosition;

            @Override
            public boolean hasNext()
            {
                return iteratorPosition != block.getPositionCount();
            }

            @Override
            public Slice next()
            {
                if (!hasNext()) {
                    throw new NoSuchElementException("Slices have been consumed");
                }
                return GEOMETRY.getSlice(block, iteratorPosition++);
            }
        };
    }

    private static Iterable<OGCGeometry> flattenCollection(OGCGeometry geometry)
    {
        if (geometry == null) {
            return ImmutableList.of();
        }
        if (!(geometry instanceof OGCConcreteGeometryCollection)) {
            return ImmutableList.of(geometry);
        }
        if (((OGCConcreteGeometryCollection) geometry).numGeometries() == 0) {
            return ImmutableList.of();
        }
        return () -> new GeometryCollectionIterator(geometry);
    }

    private static class GeometryCollectionIterator
            implements Iterator<OGCGeometry>
    {
        private final Deque<OGCGeometry> geometriesDeque = new ArrayDeque<>();

        GeometryCollectionIterator(OGCGeometry geometries)
        {
            geometriesDeque.push(requireNonNull(geometries, "geometries is null"));
        }

        @Override
        public boolean hasNext()
        {
            if (geometriesDeque.isEmpty()) {
                return false;
            }
            while (geometriesDeque.peek() instanceof OGCConcreteGeometryCollection) {
                OGCGeometryCollection collection = (OGCGeometryCollection) geometriesDeque.pop();
                for (int i = 0; i < collection.numGeometries(); i++) {
                    geometriesDeque.push(collection.geometryN(i));
                }
            }
            return !geometriesDeque.isEmpty();
        }

        @Override
        public OGCGeometry next()
        {
            if (!hasNext()) {
                throw new NoSuchElementException("Geometries have been consumed");
            }
            return geometriesDeque.pop();
        }
    }
}
