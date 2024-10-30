import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.util.ArrayList;
import java.util.Collections;

public class GammaIndex_Calculation implements PlugIn {

    public void run(String arg) {
        // Carregar as imagens de dose planejada e medida
        IJ.showMessage("Selecione a imagem de dose planejada");
        ImagePlus dosePlanned = IJ.openImage();
        
        if (dosePlanned == null) {
            IJ.showMessage("Erro", "N�o foi poss�vel carregar a imagem de dose planejada.");
            return;
        }

        IJ.showMessage("Selecione a imagem de dose medida");
        ImagePlus doseMeasured = IJ.openImage();
        
        if (doseMeasured == null) {
            IJ.showMessage("Erro", "N�o foi poss�vel carregar a imagem de dose medida.");
            return;
        }

        // Obter e exibir o tamanho do pixel de ambas as imagens
        double pixelWidthPlanned = dosePlanned.getCalibration().pixelWidth;
        double pixelHeightPlanned = dosePlanned.getCalibration().pixelHeight;
        double pixelWidthMeasured = doseMeasured.getCalibration().pixelWidth;
        double pixelHeightMeasured = doseMeasured.getCalibration().pixelHeight;

        IJ.showMessage("Tamanho do Pixel",
            "Imagem Planejada:\n" +
            "Largura do Pixel: " + pixelWidthPlanned + " mm\n" +
            "Altura do Pixel: " + pixelHeightPlanned + " mm\n\n" +
            "Imagem Medida:\n" +
            "Largura do Pixel: " + pixelWidthMeasured + " mm\n" +
            "Altura do Pixel: " + pixelHeightMeasured + " mm");

        // Definir par�metros de aceita��o
        GenericDialog gd = new GenericDialog("Par�metros de �ndice Gama");
        gd.addNumericField("Crit�rio de dose (%)", 3.0, 1);
        gd.addNumericField("Dist�ncia de aceita��o (mm)", 3.0, 1);
        gd.addNumericField("Tamanho da vizinhan�a (3, 5, 7, etc.)", 3, 0); // Definir tamanho da vizinhan�a
        gd.showDialog();
        if (gd.wasCanceled()) return;

        double doseCriterion = gd.getNextNumber();
        double distanceCriterion = gd.getNextNumber();
        int neighborhoodSize = (int) gd.getNextNumber(); // Obter tamanho da vizinhan�a

        // Certificar que o tamanho da vizinhan�a � �mpar (3x3, 5x5, 7x7, etc.)
        if (neighborhoodSize % 2 == 0) {
            IJ.showMessage("Erro", "O tamanho da vizinhan�a deve ser �mpar.");
            return;
        }

        // Pegar os processadores de imagem
        ImageProcessor ipPlanned = dosePlanned.getProcessor();
        ImageProcessor ipMeasured = doseMeasured.getProcessor();

        int width = ipPlanned.getWidth();
        int height = ipPlanned.getHeight();

        // Cria��o da nova imagem para salvar o �ndice gama
        ImagePlus gammaImage = IJ.createImage("Gamma Index", "8-bit", width, height, 1);
        ImageProcessor ipGamma = gammaImage.getProcessor();

        ArrayList<Double> gammaValues = new ArrayList<>();
        double totalGamma = 0.0;
        int count = 0;

        // Loop sobre todos os pixels
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double dosePlannedValue = ipPlanned.getPixelValue(x, y);
                double doseMeasuredValue = ipMeasured.getPixelValue(x, y);
                
                if (dosePlannedValue == 0) {
                    ipGamma.putPixelValue(x, y, 0); // Valor m�ximo para indicar erro
                    continue;
                }

                double gammaValue = calculateGammaForPixel(x, y, dosePlannedValue, doseMeasuredValue, ipPlanned, ipMeasured, doseCriterion, distanceCriterion, pixelWidthPlanned, pixelHeightPlanned, neighborhoodSize);
                
                ipGamma.putPixelValue(x, y, gammaValue * 255); // Normalizar para a escala de 8-bit

                totalGamma += gammaValue;
                gammaValues.add(gammaValue);
                count++;
            }
        }

        // Mostrar a imagem de �ndice gama
        gammaImage.show();

        // Calcular estat�sticas
        double averageGamma = totalGamma / count;
        Collections.sort(gammaValues);
        double minGamma = gammaValues.get(0);
        double maxGamma = gammaValues.get(gammaValues.size() - 1);
        double stdDevGamma = calculateStandardDeviation(gammaValues, averageGamma);

        // Calcular percentual de pixels dentro do crit�rio
        int withinCriteriaCount = 0;
        for (double gamma : gammaValues) {
            if (gamma <= 1.0) { // Crit�rio de aceita��o � 1.0
                withinCriteriaCount++;
            }
        }
        double percentageWithinCriteria = 100.0 * withinCriteriaCount / count;

        // Exibir estat�sticas do �ndice gama
        IJ.showMessage("Estat�sticas do �ndice Gama",
            "M�dia do �ndice Gama: " + averageGamma + "\n" +
            "Valor M�ximo do �ndice Gama: " + maxGamma + "\n" +
            "Valor M�nimo do �ndice Gama: " + minGamma + "\n" +
            "Desvio Padr�o do �ndice Gama: " + stdDevGamma + "\n" +
            "Percentual de Pixels Dentro do Crit�rio: " + percentageWithinCriteria + "%");
    }

    private double calculateGammaForPixel(int x, int y, double dosePlannedValue, double doseMeasuredValue,
                                          ImageProcessor ipPlanned, ImageProcessor ipMeasured,
                                          double doseCriterion, double distanceCriterion,
                                          double pixelWidth, double pixelHeight, int neighborhoodSize) {
        double minGamma = Double.MAX_VALUE;
        int width = ipPlanned.getWidth();
        int height = ipPlanned.getHeight();

        // Crit�rio de dose e dist�ncia
        double doseDiffCriterion = doseCriterion / 100.0;
        double distanceCriterionSquared = distanceCriterion * distanceCriterion;

        // Definir a metade da vizinhan�a para calcular o deslocamento
        int halfNeighborhood = neighborhoodSize / 2;

        // Loop sobre a vizinhan�a ao redor do pixel
        for (int i = -halfNeighborhood; i <= halfNeighborhood; i++) {
            for (int j = -halfNeighborhood; j <= halfNeighborhood; j++) {
                int newX = x + i;
                int newY = y + j;

                if (newX >= 0 && newX < width && newY >= 0 && newY < height) {
                    double doseNeighborPlanned = ipPlanned.getPixelValue(newX, newY);
                    double doseNeighborMeasured = ipMeasured.getPixelValue(newX, newY);

                    // Diferen�a de dose
                    double doseDiff = Math.abs(doseNeighborMeasured - dosePlannedValue) / dosePlannedValue;

                    // Dist�ncia levando em considera��o o tamanho dos pixels
                    double distanceSquared = Math.pow(i * pixelWidth, 2) + Math.pow(j * pixelHeight, 2);

                    // C�lculo do valor gama combinado
                    double combinedGamma = Math.sqrt(Math.pow(doseDiff / doseDiffCriterion, 2) + Math.pow(distanceSquared / distanceCriterionSquared, 2));

                    if (combinedGamma < minGamma) {
                        minGamma = combinedGamma;
                    }
                }
            }
        }

        return minGamma;
    }

    private double calculateStandardDeviation(ArrayList<Double> values, double mean) {
        double sumSquaredDiffs = 0.0;
        for (double value : values) {
            sumSquaredDiffs += Math.pow(value - mean, 2);
        }
        return Math.sqrt(sumSquaredDiffs / values.size());
    }
}
