/*
 * Dog - Addons
 * 
 * Copyright (c) 2012-2014 Luigi De Russis
 * 
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
 * limitations under the License
 */
package it.polito.elite.dog.addons.power;

import it.polito.elite.dog.addons.powermodel.api.DevicePowerConsumption;
import it.polito.elite.dog.addons.powermodel.api.PowerModel;
import it.polito.elite.dog.core.library.model.ControllableDevice;
import it.polito.elite.dog.core.library.model.DeviceCostants;
import it.polito.elite.dog.core.library.model.DeviceDescriptor;
import it.polito.elite.dog.core.library.model.DeviceStatus;
import it.polito.elite.dog.core.library.model.notification.Notification;
import it.polito.elite.dog.core.library.model.notification.SinglePhaseActivePowerMeasurementNotification;
import it.polito.elite.dog.core.library.model.state.State;
import it.polito.elite.dog.core.library.model.statevalue.StateValue;
import it.polito.elite.dog.core.library.util.EventFactory;
import it.polito.elite.dog.core.library.util.LogHelper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

import javax.measure.DecimalMeasure;
import javax.measure.quantity.Power;
import javax.measure.unit.Unit;

import org.osgi.framework.BundleContext;
import org.osgi.service.device.Constants;
import org.osgi.service.device.Device;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.log.LogService;
import org.osgi.service.monitor.MonitorAdmin;
import org.osgi.service.monitor.StatusVariable;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Dog Power Bundle: starting from active power notifications and states
 * notifications, this bundle tries to give the most precise disaggregated power
 * estimation for each device present in the house model. It requires the
 * {@link PowerModel} bundle to properly work.
 * 
 * @author <a href="mailto:luigi.derussis@polito.it">Luigi De Russis</a>
 * 
 */
public class PowerExtension implements EventHandler
{
	// OSGi framework reference
	private BundleContext context;
	
	// Dog logger
	private LogHelper logger;
	
	// Reference to the MonitorAdmin
	private AtomicReference<MonitorAdmin> monitorAdmin;
	// Reference to the DogPowerModelService
	private AtomicReference<PowerModel> powerModel;
	
	private AtomicReference<EventAdmin> eventAdmin;
	
	// Reference to the registration of the EventHandler
	private ServiceRegistration<?> srEventHandler;
	
	// Internal map to store power values for each device state
	private HashMap<String, DecimalMeasure<Power>> powerTable;
	
	public PowerExtension()
	{
		this.monitorAdmin = new AtomicReference<MonitorAdmin>();
		this.powerModel = new AtomicReference<PowerModel>();
		this.eventAdmin = new AtomicReference<EventAdmin>();
		// initialize the map
		powerTable = new HashMap<String, DecimalMeasure<Power>>();
	}
	
	/**
	 * Bundle activation, stores a reference to the context object passed by the
	 * framework to get access to system data, e.g., installed bundles, etc.
	 * 
	 * @param context
	 *            the bundle context
	 */
	public void activate(BundleContext context)
	{
		// store the bundle context
		this.context = context;
		
		// init the logger
		this.logger = new LogHelper(this.context);
		
		// log the bundle activation
		this.logger.log(LogService.LOG_INFO, "Activated....");
		
		// If all the acquired services are available register bundle services
		if (this.monitorAdmin.get() != null && this.powerModel.get() != null)
		{
			this.registerService();
		}
	}
	
	/**
	 * Prepare the bundle to be deactivated...
	 */
	public void deactivate()
	{
		// null the context
		this.context = null;
		
		// log deactivation
		this.logger.log(LogService.LOG_INFO, "Deactivated...");
		
		// null the logger
		this.logger = null;
	}
	
	/***
	 * Register the services exported by the bundle
	 */
	private void registerService()
	{
		// register the EventHandler service
		Hashtable<String, Object> p = new Hashtable<String, Object>();
		// Add this bundle as a listener of the MonitorAdmin events and of the
		// SinglePhaseActivePowerMeasurementNotification events
		p.put(EventConstants.EVENT_TOPIC, new String[] { "org/osgi/service/monitor",
				"it/polito/elite/dog/core/library/model/notification/SinglePhaseActivePowerMeasurementNotification" });
		this.srEventHandler = this.context.registerService(EventHandler.class.getName(), this, p);
	}
	
	public void unRegisterService()
	{
		if (this.srEventHandler != null)
		{
			this.srEventHandler.unregister();
			this.srEventHandler = null;
		}
	}
	
	/**
	 * Bind the OntologyModel service (before the bundle activation)
	 * 
	 * @param houseModel
	 *            the OntologyModel service to add
	 */
	public void addedPowerModel(PowerModel powerModel)
	{
		// store a reference to the HouseModel service
		this.powerModel.set(powerModel);
	}
	
	/**
	 * Unbind the OntologyModel service
	 * 
	 * @param houseModel
	 *            the OntologyModel service to remove
	 */
	public void removedPowerModel(PowerModel powerModel)
	{
		this.powerModel.compareAndSet(powerModel, null);
	}
	
	/**
	 * Bind the OntologyModel service (before the bundle activation)
	 * 
	 * @param houseModel
	 *            the OntologyModel service to add
	 */
	public void addedMonitorAdmin(MonitorAdmin monitorAdmin)
	{
		// store a reference to the HouseModel service
		this.monitorAdmin.set(monitorAdmin);
	}
	
	/**
	 * Unbind the OntologyModel service
	 * 
	 * @param houseModel
	 *            the OntologyModel service to remove
	 */
	public void removedMonitorAdmin(MonitorAdmin monitorAdmin)
	{
		this.monitorAdmin.compareAndSet(monitorAdmin, null);
	}
	
	/**
	 * Bind the OntologyModel service (before the bundle activation)
	 * 
	 * @param houseModel
	 *            the OntologyModel service to add
	 */
	public void addedEventAdmin(EventAdmin eventAdmin)
	{
		// store a reference to the HouseModel service
		this.eventAdmin.set(eventAdmin);
	}
	
	/**
	 * Unbind the OntologyModel service
	 * 
	 * @param houseModel
	 *            the OntologyModel service to remove
	 */
	public void removedEventAdmin(EventAdmin eventAdmin)
	{
		this.eventAdmin.compareAndSet(eventAdmin, null);
	}
	
	@Override
	public void handleEvent(Event event)
	{
		// Handle events...
		String eventTopic = event.getTopic();
		
		// debug
		this.logger.log(LogService.LOG_DEBUG, "received event: " + event);
		
		// Received a generic MonitorAdmin event (No MonitoringJob, so
		// mon.listener.id property is null)
		if (eventTopic != null && eventTopic.equals("org/osgi/service/monitor/MonitorEvent")
				&& event.getProperty("mon.listener.id") == null)
		{
			DeviceStatus currentDeviceState = null;
			try
			{
				// Try the deserialization of the DeviceStatus (property
				// mon.statusvariable.value)
				currentDeviceState = DeviceStatus.deserializeFromString((String) event
						.getProperty("mon.statusvariable.value"));
			}
			catch (Exception e)
			{
				this.logger.log(LogService.LOG_ERROR, "device status deserialization error "
						+ e.getClass().getSimpleName());
			}
			
			Notification receivedNotification = null;
			
			// If the deserialization works
			if (currentDeviceState != null)
			{
				Map<String, State> allStates = currentDeviceState.getStates();
				for (State current : allStates.values())
				{
					// debug
					this.logger.log(LogService.LOG_DEBUG, "handling state changed notification: "
							+ receivedNotification);
					
					// add the most accurate consumption for the device state,
					// if any
					this.handleStateChanged(currentDeviceState.getDeviceURI(), current);
				}
			}
		}
		// Received a SinglePhaseActivePowerMeasurementNotification
		else if (eventTopic != null
				&& eventTopic
						.equals("it/polito/elite/dog/core/library/model/notification/SinglePhaseActivePowerMeasurementNotification"))
		{
			// get the notification event inside the received message
			
			// debug
			this.logger.log(LogService.LOG_DEBUG, "received event: " + event);
			
			// get the type of event: power notification or state changed
			// notification
			Object eventContent = event.getProperty(EventConstants.EVENT);
			
			Notification receivedNotification = null;
			
			// power notification
			if (eventContent instanceof Notification)
			{
				// get the name of the bundle that generates the notification
				// (to ignore notifications generated
				// by itself), if any
				String notificationGeneratorBundle = "";
				Object bundleSymbolicName = event.getProperty(EventConstants.BUNDLE_SYMBOLICNAME);
				if (bundleSymbolicName != null)
				{
					notificationGeneratorBundle = bundleSymbolicName.toString();
					
					// debug
					this.logger.log(LogService.LOG_DEBUG, "The received notification comes from the bundle: "
							+ notificationGeneratorBundle);
				}
				
				// handle the notification if it has not been generated by the
				// bundle itself...
				if (!notificationGeneratorBundle.equals(this.getClass().getSimpleName()))
				{
					// store the received notification
					receivedNotification = (Notification) eventContent;
					
					// debug
					this.logger.log(LogService.LOG_DEBUG, "handling notification: " + receivedNotification);
					
					// update the consumption estimation
					this.handlePowerNotification(receivedNotification);
				}
				
			}
		}
		
	}
	
	/**
	 * Once a state change has been received, if the couple
	 * (deviceURI, StateValue) given by the notification is unknown to the
	 * bundle, insert it in the internal table and get the most accurate power
	 * consumption for the device state value. Otherwise, get the consumption
	 * from the internal table. In any case, generate a power notification as
	 * output.
	 * 
	 * TODO Check if it works in this way, no statechangenotification anymore
	 */
	private void handleStateChanged(String deviceURI, State currentState)
	{
		// init
		DecimalMeasure<Power> bestConsumption = null;
		HashMap<String, DevicePowerConsumption> retrievedConsumptions = null;
		
		// get the device state values
		StateValue[] currentStateValues = currentState.getCurrentStateValue();
		
		// check if the device AND its state value is already known by the
		// bundle...
		for (StateValue stValue : currentStateValues)
		{
			// debug
			this.logger.log(LogService.LOG_DEBUG, "Received a state change for " + deviceURI
					+ "; the current state value is " + stValue.getName());
			
			// if the device with the current state value is already inserted in
			// the internal table, get it as best
			// consumption
			if (powerTable.containsKey(deviceURI + "_" + stValue.getName()))
			{
				bestConsumption = powerTable.get(deviceURI + "_" + stValue.getName());
				
				generatePowerNotification(deviceURI, bestConsumption);
			}
			else
			{
				// get the best available consumption for the current device and
				// for the current state value, if any
				StateValue[] currentStateValue = new StateValue[1];
				currentStateValue[0] = stValue;
				
				retrievedConsumptions = this.getBestConsumptions(deviceURI, currentStateValue);
			}
		}
		
		if ((retrievedConsumptions != null) && !(retrievedConsumptions.isEmpty()))
		{
			// get the consumption for each state value...
			for (String currentStateValueName : retrievedConsumptions.keySet())
			{
				bestConsumption = retrievedConsumptions.get(currentStateValueName).getConsumption();
				
				// put the best power consumption in the internal table
				powerTable.put(deviceURI + "_" + currentStateValueName, bestConsumption);
				
				// debug
				this.logger.log(LogService.LOG_DEBUG, "Power value stored for " + deviceURI + " in the state "
						+ currentStateValueName + ": " + bestConsumption);
				
				generatePowerNotification(deviceURI, bestConsumption);
			}
			
		}
		
	}
	
	/**
	 * Once a SinglePhaseActivePowerMeasurementNotification has been received
	 * from a multi-devices meter, disaggregate the measurement upon the
	 * corresponding devices and update the internal table that stores the most
	 * accurate power consumption of those devices. Then, generate the related
	 * power notifications as output. If the received notification is related to
	 * a device that also act like a meter, only update the power consumption in
	 * the internal table for such a device.
	 * 
	 * @param receivedNotification
	 *            the received SinglePhaseActiveEnergyMeasurementNotification
	 */
	@SuppressWarnings("unchecked")
	private void handlePowerNotification(Notification receivedNotification)
	{
		SinglePhaseActivePowerMeasurementNotification powerNotification = (SinglePhaseActivePowerMeasurementNotification) receivedNotification;
		
		// take the device URI
		String deviceURI = powerNotification.getDeviceUri();
		
		// get the (total) power consumption
		DecimalMeasure<Power> totalPowerValue = (DecimalMeasure<Power>) powerNotification.getPowerValue();
		
		// store the metered objects for the current device, if any
		Set<String> meteredObjects = this.getDevice(deviceURI).getDeviceDescriptor().getMeterOf();
		
		// is the device a "multi-meter" (i.e., has at least one meterOf
		// property)? Split its measurements upon the
		// corresponding devices
		if (meteredObjects != null)
		{
			handleMeteredObjectPowerNotification(meteredObjects, totalPowerValue);
		}
		// is the device a "single-meter"? Only update the internal table
		else
		{
			// Get the status variable with path "deviceURI/status"
			StatusVariable deviceStatusVariable = monitorAdmin.get().getStatusVariable(deviceURI + "/status");
			DeviceStatus currentDeviceStatus = null;
			try
			{
				// Try the deserialization of the DeviceStatus
				currentDeviceStatus = DeviceStatus.deserializeFromString((String) deviceStatusVariable.getString());
			}
			catch (Exception e)
			{
				this.logger.log(LogService.LOG_ERROR, "device status deserialization error "
						+ e.getClass().getSimpleName());
			}
			
			// If the deserialization works
			if (currentDeviceStatus != null)
			{
				// get the current device state(s)
				Map<String, State> currentDeviceStates = currentDeviceStatus.getStates();
				
				for (State state : currentDeviceStates.values())
				{
					// only one state value, probably...
					for (StateValue stValue : state.getCurrentStateValue())
					{
						// update the current power consumption in the internal
						// table
						powerTable.put(deviceURI + "_" + stValue.getName(), totalPowerValue);
					}
				}
			}
		}
	}
	
	/**
	 * Handle the power estimation of metered devices (i.e., devices with the
	 * hasMeter property). The method take the list of metered device and, for
	 * each state and state values, calculate the power estimation for each
	 * device. It uses a "simple" linear repartition of consumptions, according
	 * to the typical/nominal/actual values stored in the ontology, and the
	 * aggregated power value coming from the meter. The method outcome is a
	 * power notification for each device, and the update of the internal table
	 * that stores the device most accurate power consumption.
	 * 
	 * @param meteredObjects
	 *            URI of the metered devices
	 * @param totalPowerValue
	 *            the aggregated power value stored in the meter notification
	 */
	@SuppressWarnings("unchecked")
	private void handleMeteredObjectPowerNotification(Set<String> meteredObjects, DecimalMeasure<Power> totalPowerValue)
	{
		// init
		HashMap<String, DevicePowerConsumption> retrievedConsumptions = new HashMap<String, DevicePowerConsumption>();
		HashMap<String, BigDecimal> numeratorValues = new HashMap<String, BigDecimal>();
		HashMap<String, Unit<?>> numeratorUnits = new HashMap<String, Unit<?>>();
		
		// store the total retrieved consumptions (value and unit)
		BigDecimal estimationDenominator = new BigDecimal(0);
		Unit<?> estimatedUnitDenominator = null;
		
		// store the estimated value and unit
		BigDecimal estimatedValue = new BigDecimal(0);
		Unit<?> estimatedUnit = null;
		
		// store the final estimated power value
		DecimalMeasure<Power> estimatedPowerValue = null;
		
		// debug
		this.logger.log(LogService.LOG_DEBUG, "Handling (metered) power consumptions of " + meteredObjects.size()
				+ " device(s)...");
		
		// take the current state(s) of each metered device...
		for (String meteredObject : meteredObjects)
		{
			// check if the metered device is a plug of something else...
			// create filter for getting the desired device
			String deviceFilter = String.format("(&(%s=*)(%s=%s))", Constants.DEVICE_CATEGORY,
					DeviceCostants.DEVICEURI, meteredObject);
			try
			{
				// get the device service references
				ServiceReference<?>[] deviceService = this.context.getAllServiceReferences(Device.class.getName(),
						deviceFilter);
				// only one device with the given deviceId can exists in the
				// framework...
				if (deviceService != null && deviceService.length == 1)
				{
					// get the OSGi service pointed by the current device
					// reference
					Object device = this.context.getService(deviceService[0]);
					
					if ((device != null) && (device instanceof ControllableDevice))
					{
						// get the device instance
						ControllableDevice currentDevice = (ControllableDevice) device;
						// get the associated device descriptor
						DeviceDescriptor currentDeviceDescr = currentDevice.getDeviceDescriptor();
						
						// assign the power consumption to the plugged object
						String pluggedDevice = currentDeviceDescr.getPlugOf();
						if (pluggedDevice != null && !pluggedDevice.isEmpty())
						{
							meteredObject = pluggedDevice;
						}
					}
				}
			}
			catch (Exception e)
			{
				this.logger.log(LogService.LOG_WARNING, "Exception in getting " + meteredObject
						+ " from the OSGi framework");
			}
			
			// Get the status variable with path "deviceURI/status"
			StatusVariable deviceStatusVariable = monitorAdmin.get().getStatusVariable(meteredObject + "/status");
			DeviceStatus currentDevState = null;
			try
			{
				// Try the deserialization of the DeviceStatus
				currentDevState = DeviceStatus.deserializeFromString((String) deviceStatusVariable.getString());
			}
			catch (Exception e)
			{
				this.logger.log(LogService.LOG_ERROR, "device status deserialization error "
						+ e.getClass().getSimpleName());
			}
			
			Map<String, State> currentDeviceStates = null;
			Vector<StateValue> currentStateValues = new Vector<StateValue>();
			
			// if the metered device is not (yet) active, do nothing...
			if (currentDevState != null)
			{
				currentDeviceStates = currentDevState.getStates();
			}
			else
			{
				this.logger.log(LogService.LOG_INFO, "The metered device " + meteredObject + " is not (yet) active...");
			}
			
			// for each state, get its state values ...
			if ((currentDeviceStates != null) && !(currentDeviceStates.isEmpty()))
			{
				for (State state : currentDeviceStates.values())
				{
					for (StateValue stValue : state.getCurrentStateValue())
					{
						currentStateValues.add(stValue);
					}
				}
			}
			// init the state values "standard" array
			StateValue[] stArray = new StateValue[currentStateValues.size()];
			
			// for each state value, get the best consumption...
			if ((currentStateValues != null) && !(currentStateValues.isEmpty()))
			{
				retrievedConsumptions = this.getBestConsumptions(meteredObject, currentStateValues.toArray(stArray));
			}
			
			/** linear repartition numerator and denominator **/
			if ((retrievedConsumptions != null) && !(retrievedConsumptions.isEmpty()))
			{
				// debug
				this.logger.log(LogService.LOG_DEBUG, "Starting linear repartition for power consumptions of "
						+ meteredObject + "...");
				
				// handle the most generic case; probably most devices will have
				// only a single state value...
				for (String stateValueName : retrievedConsumptions.keySet())
				{
					for (StateValue stValue : currentStateValues)
					{
						if (stateValueName.equals(stValue.getName()))
						{
							DevicePowerConsumption savedDeviceConsumption = retrievedConsumptions
									.get(stValue.getName());
							
							/** numerator **/
							BigDecimal estimatedNumerator = (totalPowerValue.getValue().multiply(savedDeviceConsumption
									.getConsumption().getValue()));
							Unit<?> estimatedUnitNumerator = (totalPowerValue.getUnit().times(savedDeviceConsumption
									.getConsumption().getUnit()));
							
							// fill the maps for the final result
							numeratorValues.put(meteredObject + "_" + stateValueName, estimatedNumerator);
							numeratorUnits.put(meteredObject + "_" + stateValueName, estimatedUnitNumerator);
							
							/** denominator **/
							// calculate the total retrieved consumption for
							// metered devices (value and unit)
							estimationDenominator = estimationDenominator.add(savedDeviceConsumption.getConsumption()
									.getValue());
							Unit<?> retrievedConsumptionUnit = savedDeviceConsumption.getConsumption().getUnit();
							
							// TODO what happens if there are different unit of
							// measure? Handle it!
							estimatedUnitDenominator = retrievedConsumptionUnit;
						}
					}
				}
			}
		}
		
		/** linear repartition - final result **/
		for (String devWithStateValue : numeratorValues.keySet())
		{
			// get the numerator to check if it is zero or not
			estimatedValue = numeratorValues.get(devWithStateValue);
			
			// unit of measure must be calculated in any case...
			estimatedUnit = numeratorUnits.get(devWithStateValue).divide(estimatedUnitDenominator);
			
			// if the numerator is zero, avoid further calculations and generate
			// the final notification (probably, the
			// device is still off)...
			if (estimatedValue.compareTo(new BigDecimal(0)) == 0)
			{
				// final value
				estimatedPowerValue = new DecimalMeasure<Power>(estimatedValue, (Unit<Power>) estimatedUnit);
			}
			else
			{
				// check not to divide by zero
				if (estimationDenominator.compareTo(new BigDecimal(0)) != 0)
				{
					// numerator/denominator values
					estimatedValue = numeratorValues.get(devWithStateValue).divide(estimationDenominator);
					
					// final value
					estimatedPowerValue = new DecimalMeasure<Power>(estimatedValue, (Unit<Power>) estimatedUnit);
				}
			}
			
			// get the device URI from the map
			String deviceURI = devWithStateValue.substring(0, devWithStateValue.lastIndexOf("_"));
			
			// if the estimated power value is still null, something was going
			// terribly wrong!
			if (estimatedPowerValue != null)
			{
				// update the internal table
				powerTable.put(devWithStateValue, estimatedPowerValue);
				
				// debug
				this.logger.log(LogService.LOG_DEBUG, "Successfully estimated power consumption for metered device: "
						+ deviceURI);
				
				// create a power notification
				generatePowerNotification(deviceURI, estimatedPowerValue);
			}
			else
			{
				// error
				this.logger.log(LogService.LOG_ERROR, "Something was gone wrong with the power estimation of "
						+ deviceURI);
			}
		}
	}
	
	/**
	 * Generate a SinglePhaseActivePowerMeasurementNotification for each device
	 * with an updated power consumption, as the output of the bundle.
	 * 
	 * @param powerValue
	 * @param deviceURI
	 */
	private void generatePowerNotification(String deviceURI, DecimalMeasure<Power> powerValue)
	{
		// new SinglePhaseActivePowerMeasurementNotification with the current
		// power value and its device
		SinglePhaseActivePowerMeasurementNotification notificationEvent = new SinglePhaseActivePowerMeasurementNotification(
				powerValue);
		notificationEvent.setDeviceUri(deviceURI);
		
		// Use the factory to create the event
		// insert the bundle name inside the event properties in order not to
		// process such "fake" power notifications
		Event eventToSend = EventFactory.createEvent(notificationEvent, this.getClass().getSimpleName());
		
		// get a pointer to the event admin service
		EventAdmin ea = this.eventAdmin.get();
		
		// send the event
		if (ea != null)
		{
			ea.postEvent(eventToSend);
			
			// debug
			this.logger.log(LogService.LOG_DEBUG, "Sending a power notification for " + deviceURI + " with value(s): "
					+ powerValue);
		}
	}
	
	/**
	 * This method gets the best consumption from the Power model and returns a
	 * map containing the corresponding power value for each current state
	 * values of the given device
	 * 
	 * @param deviceUri
	 *            the URI of the device to look for the best consumption
	 * @param currentStateValues
	 *            all the current state values of the device identified by
	 *            deviceUri
	 * @return a Map that stores the couple (state value name, power
	 *         consumption)
	 */
	private HashMap<String, DevicePowerConsumption> getBestConsumptions(String deviceUri,
			StateValue[] currentStateValues)
	{
		// the returning Map
		HashMap<String, DevicePowerConsumption> retrievedConsumptions = new HashMap<String, DevicePowerConsumption>();
		
		// temporary variable to store the consumption got from the model
		DevicePowerConsumption bestConsumption = null;
		
		// only one state value is present for the current device
		if (currentStateValues.length == 1)
		{
			StateValue currentStateValue = currentStateValues[0];
			
			// check if exist a current state value (e.g., the device could not
			// have an initial state value)
			if (currentStateValue != null)
				bestConsumption = this.powerModel.get()
						.getBestDeviceConsumption(deviceUri, currentStateValue.getName());
			
			if (bestConsumption != null)
				retrievedConsumptions.put(currentStateValues[0].getName(), bestConsumption);
		}
		// if more than one state value is present for the current device,
		// retrieve additional information (i.e., the
		// state value features)
		else
		{
			for (StateValue stateValue : currentStateValues)
			{
				String currentStateValueName = stateValue.getName();
				
				// check if exist a current state value (e.g., the device could
				// not have an initial state value)
				if (currentStateValueName != null)
					bestConsumption = this.powerModel.get().getBestDeviceConsumption(deviceUri, currentStateValueName);
				
				if (bestConsumption != null)
					retrievedConsumptions.put(stateValue.getName(), bestConsumption);
			}
		}
		
		return retrievedConsumptions;
	}
	
	private ControllableDevice getDevice(String deviceURI)
	{
		ControllableDevice device = null;
		
		// create filter
		String deviceFilter = String.format("(&(%s=*)(%s=%s))", Constants.DEVICE_CATEGORY, DeviceCostants.DEVICEURI,
				deviceURI);
		
		// get the device: open the tracker
		ServiceTracker<?, ?> tracker;
		
		try
		{
			tracker = new ServiceTracker<Object, Object>(this.context, this.context.createFilter(deviceFilter), null);
			tracker.open();
			ServiceReference<?> srDevice = tracker.getServiceReference();
			
			if (srDevice != null)
			{
				Object deviceObj = this.context.getService(srDevice);
				String active = (String) srDevice.getProperty(DeviceCostants.ACTIVE);
				
				// check if the device is active
				if (active != null && !active.isEmpty() && active.equals("true"))
				{
					if (deviceObj instanceof ControllableDevice)
					{
						device = (ControllableDevice) deviceObj;
					}
				}
			}
			
			this.context.ungetService(srDevice);
		}
		catch (InvalidSyntaxException e)
		{
			this.logger.log(LogService.LOG_ERROR, "Exception in filter creation: " + e);
		}
		
		return device;
	}
	
}
