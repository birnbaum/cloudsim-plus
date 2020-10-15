package org.cloudbus.cloudsim.power.models;

import org.cloudbus.cloudsim.hosts.Host;

import java.util.function.UnaryOperator;

/**
 * Power model for data center hosts.
 */
public class PowerModelHost implements PowerModel {

    public static PowerModelHost NULL = new PowerModelHost(0 ,0) {
        @Override public PowerMeasurement measure() { return new PowerMeasurement(); }
    };

    private Host host;

    private boolean shared;
    private double staticPower;
    private double maxPower;
    private double wattPerMips;

    /**
     * Instantiates a {@link PowerModelHost} by specifying its static and max power usage.
     *
     * @param maxPower power (in watts) the host consumes under full load.
     * @param staticPower power (in watts) the host consumes when idle.
     */
    public PowerModelHost(final double staticPower, final double maxPower) {
        this.shared = false;
        this.staticPower = staticPower;
        this.maxPower = maxPower;
    }

    /**
     * Instantiates a shared {@link PowerModelHost} by specifying its watt per MIPS.
     *
     * @param wattPerMips Incremental watt per MIPS the host consumes under load.
     */
    public PowerModelHost(final double wattPerMips) {
        this.shared = true;
        this.staticPower = 0;
        this.wattPerMips = wattPerMips;
    }

    public Host getHost() {
        return host;
    }

    public void setHost(Host host) {
        this.host = host;
        if (!shared) {
            wattPerMips = (maxPower - staticPower) / host.getTotalMipsCapacity();
        }
    }

    @Override
    public PowerMeasurement measure() {
        if(!getHost().isActive()){
            return new PowerMeasurement();
        }

        double mips = host.getCpuMipsUtilization();
        if (shared) {
            return new PowerMeasurement(0, mips * wattPerMips);
        } else {
            return new PowerMeasurement(staticPower, mips * wattPerMips);
        }
    }

    public PowerMeasurement computePowerUsage(double utilizationFraction) {
        return new PowerMeasurement(staticPower, (maxPower - staticPower) * utilizationFraction);
    }

}
