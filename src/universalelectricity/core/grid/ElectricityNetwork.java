package universalelectricity.core.grid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.common.MinecraftForge;
import universalelectricity.core.block.IConductor;
import universalelectricity.core.block.IElectrical;
import universalelectricity.core.block.INetworkConnection;
import universalelectricity.core.block.INetworkProvider;
import universalelectricity.core.electricity.ElectricalEvent.ElectricityProductionEvent;
import universalelectricity.core.electricity.ElectricalEvent.ElectricityRequestEvent;
import universalelectricity.core.electricity.ElectricityPack;
import universalelectricity.core.path.Pathfinder;
import universalelectricity.core.path.PathfinderChecker;
import universalelectricity.core.vector.Vector3;
import cpw.mods.fml.common.FMLLog;

/**
 * An Electrical Network specifies a wire connection. Each wire connection line will have its own
 * electrical network.
 * 
 * !! Do not include this class if you do not intend to have custom wires in your mod. This will
 * increase future compatibility. !!
 * 
 * @author Calclavia
 * 
 */
public class ElectricityNetwork implements IElectricityNetwork
{
	public Set<TileEntity> electricalTiles = new HashSet<TileEntity>();
	public Map<TileEntity, ForgeDirection> acceptorDirections = new HashMap<TileEntity, ForgeDirection>();

	private final Set<IConductor> conductors = new HashSet<IConductor>();

	@Override
	public float produce(ElectricityPack electricity, TileEntity... ignoreTiles)
	{
		ElectricityProductionEvent evt = new ElectricityProductionEvent(electricity, ignoreTiles);
		MinecraftForge.EVENT_BUS.post(evt);

		float energy = electricity.getWatts();
		float voltage = electricity.voltage;

		if (!evt.isCanceled())
		{
			Set<TileEntity> avaliableEnergyTiles = this.getAcceptors();

			if (!avaliableEnergyTiles.isEmpty())
			{
				final float totalEnergyRequest = this.getRequest(ignoreTiles).getWatts();

				if (totalEnergyRequest > 0)
				{
					for (TileEntity tileEntity : avaliableEnergyTiles)
					{
						if (tileEntity instanceof IElectrical && !Arrays.asList(ignoreTiles).contains(tileEntity))
						{
							IElectrical electricalTile = (IElectrical) tileEntity;
							// TODO: Fix Direction
							float energyToSend = energy * (electricalTile.getRequest(ForgeDirection.UNKNOWN) / totalEnergyRequest);

							if (energyToSend > 0)
							{
								ElectricityPack electricityToSend = ElectricityPack.getFromWatts(energyToSend, voltage);

								// Calculate energy loss caused by resistance.
								float ampsReceived = electricityToSend.amperes - (electricityToSend.amperes * electricityToSend.amperes * this.getTotalResistance()) / electricityToSend.voltage;
								float voltsReceived = electricityToSend.voltage - (electricityToSend.amperes * this.getTotalResistance());

								electricityToSend = new ElectricityPack(ampsReceived, voltsReceived);

								// TODO: Fix unknown direction!
								energy -= ((IElectrical) tileEntity).receiveElectricity(ForgeDirection.UNKNOWN, electricityToSend, true);
							}
						}
					}
				}
			}
		}

		return energy;
	}

	/**
	 * @return How much electricity this network needs.
	 */
	@Override
	public ElectricityPack getRequest(TileEntity... ignoreTiles)
	{
		List<ElectricityPack> requests = new ArrayList<ElectricityPack>();

		Iterator<TileEntity> it = this.getAcceptors().iterator();

		while (it.hasNext())
		{
			TileEntity tileEntity = it.next();

			if (Arrays.asList(ignoreTiles).contains(tileEntity))
			{
				continue;
			}

			if (tileEntity instanceof IElectrical)
			{
				if (!tileEntity.isInvalid())
				{
					if (tileEntity.worldObj.getBlockTileEntity(tileEntity.xCoord, tileEntity.yCoord, tileEntity.zCoord) == tileEntity)
					{
						requests.add(ElectricityPack.getFromWatts(((IElectrical) tileEntity).getRequest(ForgeDirection.UNKNOWN), ((IElectrical) tileEntity).getVoltage()));
						continue;
					}
				}
			}

			it.remove();

		}

		ElectricityPack mergedPack = ElectricityPack.merge(requests);
		ElectricityRequestEvent evt = new ElectricityRequestEvent(mergedPack, ignoreTiles);
		MinecraftForge.EVENT_BUS.post(evt);
		return mergedPack;
	}

	/**
	 * @return Returns all producers in this electricity network.
	 */
	@Override
	public Set<TileEntity> getAcceptors()
	{
		return this.electricalTiles;
	}

	/**
	 * This function is called to refresh all conductors in this network
	 */
	@Override
	public void refresh()
	{
		this.electricalTiles.clear();

		try
		{
			Iterator<IConductor> it = this.conductors.iterator();

			while (it.hasNext())
			{
				IConductor conductor = it.next();

				if (conductor == null)
				{
					it.remove();
				}
				else if (((TileEntity) conductor).isInvalid())
				{
					it.remove();
				}
				else
				{
					conductor.setNetwork(this);
				}

				for (TileEntity acceptor : conductor.getAdjacentConnections())
				{
					if (!(acceptor instanceof IConductor))
					{
						this.electricalTiles.add(acceptor);
					}
				}
			}
		}
		catch (Exception e)
		{
			FMLLog.severe("Universal Electricity: Failed to refresh conductor.");
			e.printStackTrace();
		}
	}

	@Override
	public float getTotalResistance()
	{
		float resistance = 0;

		for (IConductor conductor : this.conductors)
		{
			resistance += conductor.getResistance();
		}

		return resistance;
	}

	@Override
	public float getLowestCurrentCapacity()
	{
		float lowestAmperage = 0;

		for (IConductor conductor : this.conductors)
		{
			if (lowestAmperage == 0 || conductor.getCurrentCapacity() < lowestAmperage)
			{
				lowestAmperage = conductor.getCurrentCapacity();
			}
		}

		return lowestAmperage;
	}

	@Override
	public Set<IConductor> getConductors()
	{
		return this.conductors;
	}

	@Override
	public void merge(IElectricityNetwork network)
	{
		if (network != null && network != this)
		{
			ElectricityNetwork newNetwork = new ElectricityNetwork();
			newNetwork.getConductors().addAll(this.getConductors());
			newNetwork.getConductors().addAll(network.getConductors());
			newNetwork.refresh();
		}
	}

	@Override
	public void split(IConductor splitPoint)
	{
		if (splitPoint instanceof TileEntity)
		{
			this.getConductors().remove(splitPoint);

			/**
			 * Loop through the connected blocks and attempt to see if there are connections between
			 * the two points elsewhere.
			 */
			TileEntity[] connectedBlocks = splitPoint.getAdjacentConnections();

			for (int i = 0; i < connectedBlocks.length; i++)
			{
				TileEntity connectedBlockA = connectedBlocks[i];

				if (connectedBlockA instanceof INetworkConnection)
				{
					for (int ii = 0; ii < connectedBlocks.length; ii++)
					{
						final TileEntity connectedBlockB = connectedBlocks[ii];

						if (connectedBlockA != connectedBlockB && connectedBlockB instanceof INetworkConnection)
						{
							Pathfinder finder = new PathfinderChecker(((TileEntity) splitPoint).worldObj, (INetworkConnection) connectedBlockB, splitPoint);
							finder.init(new Vector3(connectedBlockA));

							if (finder.results.size() > 0)
							{
								/**
								 * The connections A and B are still intact elsewhere. Set all
								 * references of wire connection into one network.
								 */

								for (Vector3 node : finder.closedSet)
								{
									TileEntity nodeTile = node.getTileEntity(((TileEntity) splitPoint).worldObj);

									if (nodeTile instanceof INetworkProvider)
									{
										if (nodeTile != splitPoint)
										{
											((INetworkProvider) nodeTile).setNetwork(this);
										}
									}
								}
							}
							else
							{
								/**
								 * The connections A and B are not connected anymore. Give both of
								 * them a new network.
								 */
								IElectricityNetwork newNetwork = new ElectricityNetwork();

								for (Vector3 node : finder.closedSet)
								{
									TileEntity nodeTile = node.getTileEntity(((TileEntity) splitPoint).worldObj);

									if (nodeTile instanceof INetworkProvider)
									{
										if (nodeTile != splitPoint)
										{
											newNetwork.getConductors().add((IConductor) nodeTile);
										}
									}
								}

								newNetwork.refresh();
							}
						}
					}
				}
			}
		}
	}

	@Override
	public String toString()
	{
		return "ElectricityNetwork[" + this.hashCode() + "|Wires:" + this.conductors.size() + "|Acceptors:" + this.electricalTiles.size() + "]";
	}

}
