/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.core.render.hires.blockmodel;

import com.flowpowered.math.TrigMath;
import com.flowpowered.math.imaginary.Complexf;
import com.flowpowered.math.imaginary.Quaternionf;
import com.flowpowered.math.matrix.Matrix3f;
import com.flowpowered.math.vector.Vector2f;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3f;
import com.flowpowered.math.vector.Vector3i;
import com.flowpowered.math.vector.Vector4f;

import de.bluecolored.bluemap.core.model.Face;
import de.bluecolored.bluemap.core.render.RenderSettings;
import de.bluecolored.bluemap.core.render.context.BlockContext;
import de.bluecolored.bluemap.core.render.context.ExtendedBlockContext;
import de.bluecolored.bluemap.core.resourcepack.BlockColorCalculator;
import de.bluecolored.bluemap.core.resourcepack.BlockModelResource;
import de.bluecolored.bluemap.core.resourcepack.BlockModelResource.Element.Rotation;
import de.bluecolored.bluemap.core.resourcepack.Texture;
import de.bluecolored.bluemap.core.resourcepack.TransformedBlockModelResource;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.Block;

/**
 * This model builder creates a BlockStateModel using the information from parsed resource-pack json files.
 */
public class ResourceModelBuilder {
	
	private static final Vector3f HALF_3F = Vector3f.ONE.mul(0.5);
	private static final Vector3f NEG_HALF_3F = HALF_3F.negate();
	private static final Vector2f HALF_2F = Vector2f.ONE.mul(0.5);
	
	private ExtendedBlockContext context;
	private RenderSettings renderSettings;
	private BlockColorCalculator colorCalculator;
	
	public ResourceModelBuilder(RenderSettings renderSettings, ExtendedBlockContext context, BlockColorCalculator colorCalculator) {
		this.context = context;
		this.renderSettings = renderSettings;
		this.colorCalculator = colorCalculator;
	}
	
	public BlockStateModel build(TransformedBlockModelResource bmr) {
		BlockStateModel model = new BlockStateModel();
		
		for (BlockModelResource.Element element : bmr.getModel().getElements()){
			model.merge(fromModelElementResource(element, bmr));
		}
		
		if (!bmr.getRotation().equals(Vector2i.ZERO)) {
			model.translate(NEG_HALF_3F);
			model.rotate(Quaternionf.fromAxesAnglesDeg(
					-bmr.getRotation().getX(),
					-bmr.getRotation().getY(),
					0
				));
			model.translate(HALF_3F);
		}
		
		return model;
	}
	
	private BlockStateModel fromModelElementResource(BlockModelResource.Element bmer, TransformedBlockModelResource bmr) {
		BlockStateModel model = new BlockStateModel();
		
		//create faces
		Vector3f min = bmer.getFrom().min(bmer.getTo());
		Vector3f max = bmer.getFrom().max(bmer.getTo());
		
		Vector3f[] c = new Vector3f[]{
			new Vector3f( min .getX(), min .getY(), min .getZ()),
			new Vector3f( min .getX(), min .getY(), max .getZ()),
			new Vector3f( max .getX(), min .getY(), min .getZ()),
			new Vector3f( max .getX(), min .getY(), max .getZ()),
			new Vector3f( min .getX(), max .getY(), min .getZ()),
			new Vector3f( min .getX(), max .getY(), max .getZ()),
			new Vector3f( max .getX(), max .getY(), min .getZ()),
			new Vector3f( max .getX(), max .getY(), max .getZ()),
		};
		
		createElementFace(model, bmr, bmer, Direction.DOWN, c[0], c[2], c[3], c[1]);
		createElementFace(model, bmr, bmer, Direction.UP, c[5], c[7], c[6], c[4]);
		createElementFace(model, bmr, bmer, Direction.NORTH, c[2], c[0], c[4], c[6]);
		createElementFace(model, bmr, bmer, Direction.SOUTH, c[1], c[3], c[7], c[5]);
		createElementFace(model, bmr, bmer, Direction.WEST, c[0], c[1], c[5], c[4]);
		createElementFace(model, bmr, bmer, Direction.EAST, c[3], c[2], c[6], c[7]);

		//rotate
		Rotation rotation = bmer.getRotation();
		if (rotation.getAngle() != 0f){
			Vector3f translation = rotation.getOrigin();
			model.translate(translation.negate());
			
			Vector3f rotAxis = rotation.getAxis().toVector().toFloat();
			
			model.rotate(Quaternionf.fromAngleDegAxis(
					rotation.getAngle(),
					rotAxis
				));

			if (rotation.isRescale()){
				Vector3f scale = 
						Vector3f.ONE
						.sub(rotAxis)
						.mul(Math.abs(TrigMath.sin(rotation.getAngle() * TrigMath.DEG_TO_RAD)))
						.mul(1 - (TrigMath.SQRT_OF_TWO - 1))
						.add(Vector3f.ONE);
				model.transform(Matrix3f.createScaling(scale));
			}
			
			model.translate(translation);
			
		}
		
		//scale down
		model.transform(Matrix3f.createScaling(1f / 16f));
		
		return model;
	}
	
	private void createElementFace(BlockStateModel model, TransformedBlockModelResource modelResource, BlockModelResource.Element element, Direction faceDir, Vector3f c0, Vector3f c1, Vector3f c2, Vector3f c3) {
		BlockModelResource.Element.Face face = element.getFaces().get(faceDir);
		
		if (face == null) return;
		
		//face culling
		if (face.getCullface() != null){
			Block b = getRotationRelativeBlock(modelResource, face.getCullface());
			if (b.isCullingNeighborFaces()) return;
		}

		//light calculation
		Block b = getRotationRelativeBlock(modelResource, faceDir);
		BlockContext bContext = context.getRelativeView(getRotationRelativeDirectionVector(modelResource, faceDir.toVector().toFloat()).toInt());
		float skyLight = b.getPassedSunLight(bContext);
		
		//filter out faces that are not skylighted
		if (skyLight == 0f && renderSettings.isExcludeFacesWithoutSunlight()) return;

		float light = 1;
		if (renderSettings.getLightShadeMultiplier() > 0) {
			float blockLight = b.getPassedBlockLight(bContext);
			light = (Math.max(skyLight, blockLight) / 15f) * renderSettings.getLightShadeMultiplier() + (1 - renderSettings.getLightShadeMultiplier());
			if (light > 1) light = 1;
			if (light < 0) light = 0;
		}

		//UV
		Vector4f uv = face.getUv().toFloat().div(16);
		
		//UV-Lock counter-rotation
		int uvLockAngle = 0;
		Vector2i rotation = modelResource.getRotation();
		if (modelResource.isUVLock()){
			Quaternionf rot = Quaternionf.fromAxesAnglesDeg(rotation.getX(), rotation.getY(), 0);
			uvLockAngle = (int) rot.getAxesAnglesDeg().dot(faceDir.toVector().toFloat());
			
			//TODO: my math has stopped working, there has to be a more consistent solution
			if (rotation.getX() >= 180 && rotation.getY() != 90 && rotation.getY() != 270) uvLockAngle += 180;
		}

		//create both triangles
		Vector2f[] uvs = new Vector2f[4];
		uvs[0] = new Vector2f(uv.getX(), uv.getW());
		uvs[1] = new Vector2f(uv.getZ(), uv.getW());
		uvs[2] = new Vector2f(uv.getZ(), uv.getY());
		uvs[3] = new Vector2f(uv.getX(), uv.getY());
		
		//face texture rotation
		uvs = rotateUVOuter(uvs, uvLockAngle);
		uvs = rotateUVInner(uvs, face.getRotation());
		
		Texture texture = face.getTexture();
		int textureId = texture.getId();
		
		Face f1 = new Face(c0, c1, c2, uvs[0], uvs[1], uvs[2], textureId);
		Face f2 = new Face(c0, c2, c3, uvs[0], uvs[2], uvs[3], textureId);
		
		//calculate ao
		double ao0 = 1d, ao1 = 1d, ao2 = 1d, ao3 = 1d;
		if (renderSettings.getAmbientOcclusionStrenght() > 0f && modelResource.getModel().isAmbientOcclusion()){
			ao0 = testAo(modelResource, c0, faceDir);
			ao1 = testAo(modelResource, c1, faceDir);
			ao2 = testAo(modelResource, c2, faceDir);
			ao3 = testAo(modelResource, c3, faceDir);
		}
		
		//tint the face
		Vector3f color = Vector3f.ONE;
		if (face.isTinted()){
			color = colorCalculator.getBlockColor(context); //TODO: cache this so we don't recalculate the tint color again for each face?
		}
	
		color = color.mul(light);
		
		Vector3f aoColor;
		
		aoColor = color.mul(ao0);
		f1.setC1(aoColor);
		f2.setC1(aoColor);
		
		aoColor = color.mul(ao1);
		f1.setC2(aoColor);
		
		aoColor = color.mul(ao2);
		f1.setC3(aoColor);
		f2.setC2(aoColor);
		
		aoColor = color.mul(ao3);
		f2.setC3(aoColor);
				
		//add the face
		model.addFace(f1);
		model.addFace(f2);
		
		//if is top face set model-color
		Vector3f dir = getRotationRelativeDirectionVector(modelResource, faceDir.toVector().toFloat());

		if (element.getRotation().getAngle() > 0){
			Quaternionf rot = Quaternionf.fromAngleDegAxis(
					element.getRotation().getAngle(),
					element.getRotation().getAxis().toVector().toFloat()
				);
			dir = rot.rotate(dir);
		}
		
		float a = dir.getY();
		if (a > 0){
			Vector4f c = texture.getColor();
			c = c.mul(color.toVector4(1f));
			c = new Vector4f(c.getX(), c.getY(), c.getZ(), c.getW() * a);
			model.mergeMapColor(c);
		}
		
	}
	
	private Block getRotationRelativeBlock(TransformedBlockModelResource model, Direction direction){
		return getRotationRelativeBlock(model, direction.toVector());
	}
	
	private Block getRotationRelativeBlock(TransformedBlockModelResource model, Vector3i direction){
		Vector3i dir = getRotationRelativeDirectionVector(model, direction.toFloat()).round().toInt();
		return context.getRelativeBlock(dir);
	}
	
	private Vector3f getRotationRelativeDirectionVector(TransformedBlockModelResource model, Vector3f direction){
		Quaternionf rot = Quaternionf.fromAxesAnglesDeg(
				-model.getRotation().getX(),
				-model.getRotation().getY(),
				0
			);
		Vector3f dir = rot.rotate(direction);
		return dir;
	}
	
	private double testAo(TransformedBlockModelResource model, Vector3f vertex, Direction dir){
		int occluding = 0;
		
		int x = 0;
		if (vertex.getX() == 16){
			x = 1;
		} else if (vertex.getX() == 0){
			x = -1;
		}
		
		int y = 0;
		if (vertex.getY() == 16){
			y = 1;
		} else if (vertex.getY() == 0){
			y = -1;
		}
		
		int z = 0;
		if (vertex.getZ() == 16){
			z = 1;
		} else if (vertex.getZ() == 0){
			z = -1;
		}

		Vector3i rel = new Vector3i(x, y, 0);
		if (rel.dot(dir.toVector()) > 0){
			if (getRotationRelativeBlock(model, rel).isOccludingNeighborFaces()) occluding++;
		}
		
		rel = new Vector3i(x, 0, z);
		if (rel.dot(dir.toVector()) > 0){
			if (getRotationRelativeBlock(model, rel).isOccludingNeighborFaces()) occluding++;
		}
		
		rel = new Vector3i(0, y, z);
		if (rel.dot(dir.toVector()) > 0){
			if (getRotationRelativeBlock(model, rel).isOccludingNeighborFaces()) occluding++;
		}
		
		rel = new Vector3i(x, y, z);
		if (rel.dot(dir.toVector()) > 0){
			if (getRotationRelativeBlock(model, rel).isOccludingNeighborFaces()) occluding++;
		}
		
		if (occluding > 3)
		occluding = 3;
		
		return Math.max(0.0, Math.min(1.0 - ((double) occluding * renderSettings.getAmbientOcclusionStrenght()), 1.0));
	}
	
	private Vector2f[] rotateUVInner(Vector2f[] uv, int angle){
		if (uv.length == 0) return uv;
		
		int steps = getRotationSteps(angle); 
		
		for (int i = 0; i < steps; i++){
			Vector2f first = uv[uv.length - 1];
			System.arraycopy(uv, 0, uv, 1, uv.length - 1);
			uv[0] = first;
		}
		
		return uv;
	}
	
	private Vector2f[] rotateUVOuter(Vector2f[] uv, float angle){
		angle %= 360;
		if (angle < 0) angle += 360;
		
		if (angle == 0) return uv;
		
		Complexf c = Complexf.fromAngleDeg(angle);
		
		for (int i = 0; i < uv.length; i++){
			uv[i] = uv[i].sub(HALF_2F);
			uv[i] = c.rotate(uv[i]);
			uv[i] = uv[i].add(HALF_2F);
		}
		
		return uv;
	}
	
	private int getRotationSteps(int angle){
		angle = -Math.floorDiv(angle, 90);
		angle %= 4;
		if (angle < 0) angle += 4;
		
		return angle;
	}
	
}
