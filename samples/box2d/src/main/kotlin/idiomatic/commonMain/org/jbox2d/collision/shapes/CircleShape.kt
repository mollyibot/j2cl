/*
 * ****************************************************************************
 * Copyright (c) 2013, Daniel Murphy All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright notice, this list of conditions
 *   and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice, this list of
 *   conditions and the following disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * ****************************************************************************
 */
package org.jbox2d.collision.shapes

import org.jbox2d.collision.AABB
import org.jbox2d.collision.RayCastInput
import org.jbox2d.collision.RayCastOutput
import org.jbox2d.common.MathUtils
import org.jbox2d.common.Settings
import org.jbox2d.common.Transform
import org.jbox2d.common.Vec2

/** A circle shape. */
class CircleShape : Shape(ShapeType.CIRCLE) {
  val p: Vec2 = Vec2()
  override var radius = 0f

  override fun clone(): Shape {
    val shape = CircleShape()
    shape.p.x = p.x
    shape.p.y = p.y
    shape.radius = radius
    return shape
  }

  override fun getChildCount(): Int = 1

  /** Get the supporting vertex index in the given direction. */
  fun getSupport(@Suppress("UNUSED_PARAMETER") d: Vec2): Int = 0

  /** Get the supporting vertex in the given direction. */
  fun getSupportVertex(@Suppress("UNUSED_PARAMETER") d: Vec2): Vec2 = p

  /** Get the vertex count. */
  val vertexCount: Int
    get() = 1

  /** Get a vertex by index. */
  fun getVertex(@Suppress("UNUSED_PARAMETER") index: Int): Vec2 = p

  override fun testPoint(xf: Transform, p: Vec2): Boolean {
    // Rot.mulToOutUnsafe(transform.q, m_p, center);
    // center.addLocal(transform.p);
    //
    // final Vec2 d = center.subLocal(p).negateLocal();
    // return Vec2.dot(d, d) <= m_radius * m_radius;
    val q = xf.q
    val tp = xf.p
    val centerx: Float = -(q.cos * this.p.x - q.sin * this.p.y + tp.x - p.x)
    val centery: Float = -(q.sin * this.p.x + q.cos * this.p.y + tp.y - p.y)
    return centerx * centerx + centery * centery <= radius * radius
  }

  // Collision Detection in Interactive 3D Environments by Gino van den Bergen
  // From Section 3.1.2
  // x = s + a * r
  // norm(x) = radius
  override fun raycast(
    output: RayCastOutput,
    input: RayCastInput,
    transform: Transform,
    childIndex: Int,
  ): Boolean {
    val inputp1 = input.p1
    val inputp2 = input.p2
    val tq = transform.q
    val tp = transform.p

    // Rot.mulToOutUnsafe(transform.q, m_p, position);
    // position.addLocal(transform.p);
    val positionx: Float = tq.cos * p.x - tq.sin * p.y + tp.x
    val positiony: Float = tq.sin * p.x + tq.cos * p.y + tp.y
    val sx = inputp1.x - positionx
    val sy = inputp1.y - positiony
    // final float b = Vec2.dot(s, s) - m_radius * m_radius;
    val b = sx * sx + sy * sy - radius * radius

    // Solve quadratic equation.
    val rx = inputp2.x - inputp1.x
    val ry = inputp2.y - inputp1.y
    // final float c = Vec2.dot(s, r);
    // final float rr = Vec2.dot(r, r);
    val c = sx * rx + sy * ry
    val rr = rx * rx + ry * ry
    val sigma = c * c - rr * b

    // Check for negative discriminant and short segment.
    if (sigma < 0.0f || rr < Settings.EPSILON) {
      return false
    }

    // Find the point of intersection of the line with the circle.
    var a = -(c + MathUtils.sqrt(sigma))

    // Is the intersection point on the segment?
    if (0.0f <= a && a <= input.maxFraction * rr) {
      a /= rr
      output.fraction = a
      output.normal.x = rx * a + sx
      output.normal.y = ry * a + sy
      output.normal.normalize()
      return true
    }
    return false
  }

  override fun computeAABB(aabb: AABB, xf: Transform, childIndex: Int) {
    val tq = xf.q
    val tp = xf.p
    val px: Float = tq.cos * p.x - tq.sin * p.y + tp.x
    val py: Float = tq.sin * p.x + tq.cos * p.y + tp.y
    aabb.lowerBound.x = px - radius
    aabb.lowerBound.y = py - radius
    aabb.upperBound.x = px + radius
    aabb.upperBound.y = py + radius
  }

  override fun computeMass(massData: MassData, density: Float) {
    massData.mass = density * Settings.PI * radius * radius
    massData.center.x = p.x
    massData.center.y = p.y

    // inertia about the local origin
    // massData.I = massData.mass * (0.5f * m_radius * m_radius + Vec2.dot(m_p, m_p));
    massData.I = massData.mass * (0.5f * radius * radius + (p.x * p.x + p.y * p.y))
  }
}
