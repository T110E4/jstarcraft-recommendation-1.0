package com.jstarcraft.recommendation.recommender.collaborative.rating;

import com.jstarcraft.ai.math.structure.DefaultScalar;
import com.jstarcraft.ai.math.structure.MathCalculator;
import com.jstarcraft.ai.math.structure.matrix.DenseMatrix;
import com.jstarcraft.ai.math.structure.vector.MathVector;
import com.jstarcraft.ai.math.structure.vector.VectorScalar;
import com.jstarcraft.recommendation.configure.Configuration;
import com.jstarcraft.recommendation.data.DataSpace;
import com.jstarcraft.recommendation.data.accessor.DataSample;
import com.jstarcraft.recommendation.data.accessor.InstanceAccessor;
import com.jstarcraft.recommendation.data.accessor.SampleAccessor;
import com.jstarcraft.recommendation.recommender.FactorizationMachineRecommender;

/**
 * 
 * FFM推荐器
 * 
 * <pre>
 * Field Aware Factorization Machines for CTR Prediction
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public class FFMRecommender extends FactorizationMachineRecommender {
	/**
	 * learning rate of stochastic gradient descent
	 */
	private float learnRate;
	/**
	 * record the <feature: filed>
	 */
	private int[] featureOrders;

	@Override
	public void prepare(Configuration configuration, SampleAccessor marker, InstanceAccessor model, DataSpace space) {
		super.prepare(configuration, marker, model, space);

		// Matrix for p * (factor * filed)
		// TODO 此处应该还是稀疏
		featureFactors = DenseMatrix.valueOf(numberOfFeatures, numberOfFactors * marker.getDiscreteOrder());
		featureFactors.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			scalar.setValue(distribution.sample().floatValue());
		});

		// init the map for feature of filed
		featureOrders = new int[numberOfFeatures];
		int count = 0;
		for (int dimension = 0; dimension < marker.getDiscreteOrder(); dimension++) {
			int size = marker.getDiscreteAttribute(dimension).getSize();
			for (int index = 0; index < size; index++) {
				featureOrders[count + index] = dimension;
			}
			count += size;
		}

		learnRate = configuration.getFloat("rec.iterator.learnRate");
	}

	@Override
	protected void doPractice() {
		DefaultScalar scalar = DefaultScalar.getInstance();
		for (int iterationStep = 0; iterationStep < numberOfEpoches; iterationStep++) {
			totalLoss = 0F;
			int outerIndex = 0;
			int innerIndex = 0;
			float outerValue = 0F;
			float innerValue = 0F;
			float oldWeight = 0F;
			float newWeight = 0F;
			float oldFactor = 0F;
			float newFactor = 0F;
			int order = marker.getDiscreteOrder();
			int[] keys = new int[order];
			for (DataSample sample : marker) {
				for (int dimension = 0; dimension < order; dimension++) {
					keys[dimension] = sample.getDiscreteFeature(dimension);
				}
				// TODO 因为每次的data都是1,可以考虑避免重复构建featureVector.
				MathVector featureVector = getFeatureVector(keys);
				float rate = sample.getMark();
				float predict = predict(scalar, featureVector);
				float error = predict - rate;
				totalLoss += error * error;

				// global bias
				totalLoss += biasRegularization * globalBias * globalBias;

				// update w0
				float hW0 = 1;
				float gradW0 = error * hW0 + biasRegularization * globalBias;
				globalBias += -learnRate * gradW0;

				// 1-way interactions
				for (VectorScalar outerTerm : featureVector) {
					outerIndex = outerTerm.getIndex();
					innerIndex = 0;
					oldWeight = weightVector.getValue(outerIndex);
					newWeight = outerTerm.getValue();
					newWeight = error * newWeight + weightRegularization * oldWeight;
					weightVector.shiftValue(outerIndex, -learnRate * newWeight);
					totalLoss += weightRegularization * oldWeight * oldWeight;
					outerValue = outerTerm.getValue();
					innerValue = 0F;
					// 2-way interactions
					for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
						oldFactor = featureFactors.getValue(outerIndex, featureOrders[outerIndex] + factorIndex);
						newFactor = 0F;
						for (VectorScalar innerTerm : featureVector) {
							innerIndex = innerTerm.getIndex();
							innerValue = innerTerm.getValue();
							if (innerIndex != outerIndex) {
								newFactor += outerValue * featureFactors.getValue(innerIndex, featureOrders[outerIndex] + factorIndex) * innerValue;
							}
						}
						newFactor = error * newFactor + factorRegularization * oldFactor;
						featureFactors.shiftValue(outerIndex, featureOrders[outerIndex] + factorIndex, -learnRate * newFactor);
						totalLoss += factorRegularization * oldFactor * oldFactor;
					}
				}
			}

			totalLoss *= 0.5;
			if (isConverged(iterationStep) && isConverged) {
				break;
			}
			currentLoss = totalLoss;
		}
	}

	@Override
	protected float predict(DefaultScalar scalar, MathVector featureVector) {
		float value = 0F;
		// global bias
		value += globalBias;
		// 1-way interaction
		value += scalar.dotProduct(weightVector, featureVector).getValue();
		int outerIndex = 0;
		int innerIndex = 0;
		float outerValue = 0F;
		float innerValue = 0F;
		// 2-way interaction
		for (int featureIndex = 0; featureIndex < numberOfFactors; featureIndex++) {
			for (VectorScalar outerVector : featureVector) {
				outerIndex = outerVector.getIndex();
				outerValue = outerVector.getValue();
				for (VectorScalar innerVector : featureVector) {
					innerIndex = innerVector.getIndex();
					innerValue = innerVector.getValue();
					if (outerIndex != innerIndex) {
						value += featureFactors.getValue(outerIndex, featureOrders[innerIndex] + featureIndex) * featureFactors.getValue(innerIndex, featureOrders[outerIndex] + featureIndex) * outerValue * innerValue;
					}
				}
			}
		}

		if (value > maximumOfScore) {
			value = maximumOfScore;
		}
		if (value < minimumOfScore) {
			value = minimumOfScore;
		}
		return value;
	}

}
