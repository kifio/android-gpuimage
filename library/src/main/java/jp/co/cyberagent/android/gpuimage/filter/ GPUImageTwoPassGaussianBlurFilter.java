/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage.filter;

/**
 * A more generalized 9x9 Gaussian blur filter blurSize value ranging from 0.0
 * on up, with a default of 1.0
 */
public class GPUImageTwoPassGaussianBlurFilter extends GPUImageTwoPassTextureSamplingFilter {

    public static GPUImageTwoPassGaussianBlurFilter create(float radius) {
//        float blurRadiusInPixels = radius;
//        blurRadiusInPixels = Math.round(blurRadiusInPixels);

        int calculatedSampleRadius = 0;
        if (radius >= 1) // Avoid a divide-by-zero error here
        {
            // Calculate the number of pixels to sample from by setting a bottom limit for the contribution of the outermost pixel
            float minimumWeightToFindEdgeOfSamplingArea = 1.0f / 256.0f;
            calculatedSampleRadius = (int) Math.floor(Math.sqrt(-2.0 * Math.pow(radius, 2.0) * Math.log(minimumWeightToFindEdgeOfSamplingArea * Math.sqrt(2.0 * Math.PI * Math.pow(radius, 2.0)))));
            calculatedSampleRadius += calculatedSampleRadius % 2; // There's nothing to gain from handling odd radius sizes, due to the optimizations I use
        }

        String fragment = fragmentShaderForOptimizedBlurOfRadius(calculatedSampleRadius, radius);
        String vertex = vertexShaderForOptimizedBlurOfRadius(calculatedSampleRadius, radius);

        return new GPUImageTwoPassGaussianBlurFilter(vertex, fragment, radius);
    }


    private static String vertexShaderForOptimizedBlurOfRadius(final int blurRadius, final float sigma) {
        if (blurRadius < 1) {
            return NO_FILTER_VERTEX_SHADER;
        }

        // First, generate the normal Gaussian weights for a given sigma
        float[] standardGaussianWeights = new float[blurRadius + 1];
        float sumOfWeights = 0.0f;
        for (int currentGaussianWeightIndex = 0; currentGaussianWeightIndex < blurRadius + 1; currentGaussianWeightIndex++) {
            standardGaussianWeights[currentGaussianWeightIndex] = (float) ((1.0 / Math.sqrt(2.0 * Math.PI * Math.pow(sigma, 2.0))) * Math.exp(-Math.pow(currentGaussianWeightIndex, 2.0) / (2.0 * Math.pow(sigma, 2.0))));

            if (currentGaussianWeightIndex == 0) {
                sumOfWeights += standardGaussianWeights[currentGaussianWeightIndex];
            } else {
                sumOfWeights += 2.0 * standardGaussianWeights[currentGaussianWeightIndex];
            }
        }

        // Next, normalize these weights to prevent the clipping of the Gaussian curve at the end of the discrete samples from reducing luminance
        for (int currentGaussianWeightIndex = 0; currentGaussianWeightIndex < blurRadius + 1; currentGaussianWeightIndex++) {
            standardGaussianWeights[currentGaussianWeightIndex] = standardGaussianWeights[currentGaussianWeightIndex] / sumOfWeights;
        }

        // From these weights we calculate the offsets to read interpolated values from
        int numberOfOptimizedOffsets = Math.min(blurRadius / 2 + (blurRadius % 2), 7);
        float[] optimizedGaussianOffsets = new float[numberOfOptimizedOffsets];

        for (int currentOptimizedOffset = 0; currentOptimizedOffset < numberOfOptimizedOffsets; currentOptimizedOffset++) {
            float firstWeight = standardGaussianWeights[currentOptimizedOffset * 2 + 1];
            float secondWeight = standardGaussianWeights[currentOptimizedOffset * 2 + 2];

            float optimizedWeight = firstWeight + secondWeight;

            optimizedGaussianOffsets[currentOptimizedOffset] = (firstWeight * (currentOptimizedOffset * 2 + 1) + secondWeight * (currentOptimizedOffset * 2 + 2)) / optimizedWeight;
        }

        String shaderString = "attribute vec4 position;\n"
                + "attribute vec4 inputTextureCoordinate;\n"
                + "\n"
                + "uniform lowp float texelWidthOffset;\n"
                + "uniform lowp float texelHeightOffset;\n"
                + "\n"
                + "varying vec2 textureCoordinate;\n"
                + "varying vec2 blurCoordinates[" + (long) (1 + (numberOfOptimizedOffsets * 2)) + "];\n"
                + "\n"
                + "void main()\n"
                + "{\n"
                + "    gl_Position = position;\n"
                + "   textureCoordinate = inputTextureCoordinate.xy;\n"
                + "    \n"
                + "    vec2 singleStepOffset = vec2(texelWidthOffset, texelHeightOffset);\n";

        // Inner offset loop
        shaderString += "    blurCoordinates[0] = inputTextureCoordinate.xy;\n";
        for (int currentOptimizedOffset = 0; currentOptimizedOffset < numberOfOptimizedOffsets; currentOptimizedOffset++) {
            shaderString += "    blurCoordinates[" + (long) ((currentOptimizedOffset * 2) + 1) + "] = inputTextureCoordinate.xy + singleStepOffset * " + optimizedGaussianOffsets[currentOptimizedOffset] + ";\n"
                    + "    blurCoordinates[" + (long) ((currentOptimizedOffset * 2) + 2) + "] = inputTextureCoordinate.xy - singleStepOffset * " + optimizedGaussianOffsets[currentOptimizedOffset] + ";\n";
        }

        // Footer
        shaderString += "}\n";
        return shaderString;
    }

    private static String fragmentShaderForOptimizedBlurOfRadius(final int blurRadius, final float sigma) {
        if (blurRadius < 1) {
            return NO_FILTER_FRAGMENT_SHADER;
        }

        // First, generate the normal Gaussian weights for a given sigma
        float[] standardGaussianWeights = new float[blurRadius + 1];
        float sumOfWeights = 0.0f;
        for (int currentGaussianWeightIndex = 0; currentGaussianWeightIndex < blurRadius + 1; currentGaussianWeightIndex++) {
            standardGaussianWeights[currentGaussianWeightIndex] = (float) ((1.0 / Math.sqrt(2.0 * Math.PI * Math.pow(sigma, 2.0))) * Math.exp(-Math.pow(currentGaussianWeightIndex, 2.0) / (2.0 * Math.pow(sigma, 2.0))));

            if (currentGaussianWeightIndex == 0) {
                sumOfWeights += standardGaussianWeights[currentGaussianWeightIndex];
            } else {
                sumOfWeights += 2.0 * standardGaussianWeights[currentGaussianWeightIndex];
            }
        }

        // Next, normalize these weights to prevent the clipping of the Gaussian curve at the end of the discrete samples from reducing luminance
        for (int currentGaussianWeightIndex = 0; currentGaussianWeightIndex < blurRadius + 1; currentGaussianWeightIndex++) {
            standardGaussianWeights[currentGaussianWeightIndex] = standardGaussianWeights[currentGaussianWeightIndex] / sumOfWeights;
        }

        // From these weights we calculate the offsets to read interpolated values from
        int numberOfOptimizedOffsets = Math.min(blurRadius / 2 + (blurRadius % 2), 7);
        int trueNumberOfOptimizedOffsets = blurRadius / 2 + (blurRadius % 2);

        // Header

        String shaderString = "uniform sampler2D inputImageTexture;\n"
                + "uniform lowp float texelWidthOffset;\n"
                + "uniform lowp float texelHeightOffset;\n"
                + "\n"
                + "varying highp vec2 blurCoordinates[" + (1 + (numberOfOptimizedOffsets * 2)) + "];\n"
                + "varying highp vec2 textureCoordinate;\n"
                + "\n"
                + "void main()\n"
                + "{\n"
                + "   lowp vec3 sum = vec3(0.0);\n"
                + "   lowp vec4 fragColor=texture2D(inputImageTexture,textureCoordinate);\n";

        // Inner texture loop
        shaderString += "    sum += texture2D(inputImageTexture, blurCoordinates[0]).rgb * " + standardGaussianWeights[0] + ";\n";

        for (int currentBlurCoordinateIndex = 0; currentBlurCoordinateIndex < numberOfOptimizedOffsets; currentBlurCoordinateIndex++) {
            float firstWeight = standardGaussianWeights[currentBlurCoordinateIndex * 2 + 1];
            float secondWeight = standardGaussianWeights[currentBlurCoordinateIndex * 2 + 2];
            float optimizedWeight = firstWeight + secondWeight;

            shaderString += "    sum += texture2D(inputImageTexture, blurCoordinates[" + ((currentBlurCoordinateIndex * 2) + 1) + "]).rgb * " + optimizedWeight + ";\n";
            shaderString += "    sum += texture2D(inputImageTexture, blurCoordinates[" + ((currentBlurCoordinateIndex * 2) + 2) + "]).rgb * " + optimizedWeight + ";\n";
        }

        // If the number of required samples exceeds the amount we can pass in via varyings, we have to do dependent texture reads in the fragment shader
        if (trueNumberOfOptimizedOffsets > numberOfOptimizedOffsets) {
            shaderString += "    highp vec2 singleStepOffset = vec2(texelWidthOffset, texelHeightOffset);\n";
            for (int currentOverlowTextureRead = numberOfOptimizedOffsets; currentOverlowTextureRead < trueNumberOfOptimizedOffsets; currentOverlowTextureRead++) {
                float firstWeight = standardGaussianWeights[currentOverlowTextureRead * 2 + 1];
                float secondWeight = standardGaussianWeights[currentOverlowTextureRead * 2 + 2];

                float optimizedWeight = firstWeight + secondWeight;
                float optimizedOffset = (firstWeight * (currentOverlowTextureRead * 2 + 1) + secondWeight * (currentOverlowTextureRead * 2 + 2)) / optimizedWeight;

                shaderString += "    sum += texture2D(inputImageTexture, blurCoordinates[0] + singleStepOffset * " + optimizedOffset + ").rgb * " + optimizedWeight + ";\n";
                shaderString += "    sum += texture2D(inputImageTexture, blurCoordinates[0] - singleStepOffset * " + optimizedOffset + ").rgb * " + optimizedWeight + ";\n";
            }
        }

        // Footer
        shaderString += "    gl_FragColor = vec4(sum,fragColor.a);\n"
                + "}\n";

        return shaderString;
    }

    protected float mBlurSize = 3f;
    protected boolean inPixels = true;

    public GPUImageTwoPassGaussianBlurFilter() {
        this(3f);
    }

    public GPUImageTwoPassGaussianBlurFilter(float blurSize) {
        this(blurSize, false);
    }

    public GPUImageTwoPassGaussianBlurFilter(String vertexShader, String fragmentShader, float blurSize) {
        super(vertexShader, fragmentShader, vertexShader, fragmentShader);
        setBlurSize(blurSize);
    }

    public GPUImageTwoPassGaussianBlurFilter(float blurSize, final boolean inPixels) {
        super(null, null, null, null);
        if (inPixels) {
            setBlurRadiusInPixels(blurSize);
        } else {
            setBlurSize(blurSize);
        }
    }

    @Override
    public void onInit() {
//        setShaders(vertex, fragment, vertex, fragment);
        super.onInit();
    }


    //    @Override
    //    public float getVerticalTexelOffsetRatio() {
    //        return mBlurSize;
    //    }
    //
    //    @Override
    //    public float getHorizontalTexelOffsetRatio() {
    //        return mBlurSize;
    //    }

    public float getBlurSize() {
        return mBlurSize;
    }

    public float getBlurRadiusInPixels() {
        return mBlurSize;
    }

    /**
     * A multiplier for the blur size, ranging from 0.0 on up, with a default of
     * 1.0
     *
     * @param blurSize from 0.0 on up, default 1.0
     */
    private void setBlurSize(float blurSize) {
        mBlurSize = blurSize;
        inPixels = false;
    }

    private void setBlurRadiusInPixels(float blurSize) {
        mBlurSize = blurSize;
        inPixels = true;
    }
}