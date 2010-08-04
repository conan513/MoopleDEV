/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.channel.handler;

import client.IItem;
import client.MapleCharacter;
import client.MapleClient;
import client.MapleInventory;
import client.MapleInventoryType;
import client.MapleRing;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import net.AbstractMaplePacketHandler;
import server.CashShop;
import server.CashShop.CashItem;
import server.CashShop.CashItemFactory;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;

public final class CashOperationHandler extends AbstractMaplePacketHandler {
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        MapleCharacter chr = c.getPlayer();
        CashShop cs = chr.getCashShop();
        if (!cs.isOpened()) {
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        final int action = slea.readByte();
        if (action == 0x03 || action == 0x1E) {
            slea.readByte();
            final int useNX = slea.readInt();
            final int snCS = slea.readInt();
            CashItem cItem = CashItemFactory.getItem(snCS);
                        if (cItem == null || !cItem.isOnSale() || cs.getCash(useNX) < cItem.getPrice())
                                return;

			if (action == 0x03) { // Item
				IItem item = cItem.toItem();
				cs.addToInventory(item);
				c.getSession().write(MaplePacketCreator.showBoughtCashItem(item, c.getAccID()));
			} else { // Package
				List<IItem> cashPackage = CashItemFactory.getPackage(cItem.getItemId());

				for (IItem item : cashPackage)
					cs.addToInventory(item);

				c.getSession().write(MaplePacketCreator.showBoughtCashPackage(cashPackage, c.getAccID()));
			}
                        cs.gainCash(useNX, -cItem.getPrice());
                        c.getSession().write(MaplePacketCreator.showCash(chr));
        } else if (action == 0x04) {
            int birthday = slea.readInt();
            CashItem cItem = CashItemFactory.getItem(slea.readInt());
            Map<String, String> recipient = MapleCharacter.getCharacterFromDatabase(slea.readMapleAsciiString());
            String message = slea.readMapleAsciiString();

            if (!canBuy(cItem, cs.getCash(4)) || message.length() < 1 || message.length() > 73)
		return;

            if (!checkBirthday(c, birthday)) {
		c.getSession().write(MaplePacketCreator.showCashShopMessage(0xC4));
		return;
            } else if (recipient == null) {
		c.getSession().write(MaplePacketCreator.showCashShopMessage(0xA9));
		return;
            } else if (recipient.get("accountId").equals(String.valueOf(c.getAccID()))) {
                c.getSession().write(MaplePacketCreator.showCashShopMessage(0xA8));
		return;
            }

            cs.gift(Integer.parseInt(recipient.get("id")), chr.getName(), message, cItem.getSN());
            c.getSession().write(MaplePacketCreator.showGiftSucceed(recipient.get("name"), cItem));
            cs.gainCash(4, -cItem.getPrice());
            c.getSession().write(MaplePacketCreator.showCash(chr));
        } else if (action == 5) { // Modify wish list
	cs.clearWishList();

	for (byte i = 0; i < 10; i++) {
	int sn = slea.readInt();
		CashItem cItem = CashItemFactory.getItem(sn);

	if (cItem != null && cItem.isOnSale() && sn != 0)
		cs.addToWishList(sn);
	}

	c.getSession().write(MaplePacketCreator.showWishList(chr, true));
        } else if (action == 7) {
            slea.readByte();
            byte toCharge = slea.readByte();
            int toIncrease = slea.readInt();
            if (cs.getCash(toCharge) >= 4000 && c.getPlayer().getStorage().getSlots() < 48) { // 48 is max.
                cs.gainCash(toCharge, -4000);
                if (toIncrease == 0) {
                    c.getPlayer().getStorage().gainSlots((byte) 4);
                }
                showCS(c);
            }
		} else if (action == 0x06) { // Increase Inventory Slots
			slea.skip(1);
			int cash = slea.readInt();
			byte mode = slea.readByte();

			if (mode == 0) {
				byte type = slea.readByte();

				if (cs.getCash(cash) < 4000)
					return;

				if (chr.gainSlots(type, 4, false)) {
					c.getSession().write(MaplePacketCreator.showBoughtInventorySlots(type, chr.getSlots(type)));
					cs.gainCash(cash, -4000);
					c.getSession().write(MaplePacketCreator.showCash(chr));
				}
			} else {
				CashItem cItem = CashItemFactory.getItem(slea.readInt());
				int type = (cItem.getItemId() - 9110000) / 1000;

				if (!canBuy(cItem, cs.getCash(cash)))
					return;

				if (chr.gainSlots(type, 8, false)) {
					c.getSession().write(MaplePacketCreator.showBoughtInventorySlots(type, chr.getSlots(type)));
					cs.gainCash(cash, -cItem.getPrice());
					c.getSession().write(MaplePacketCreator.showCash(chr));
				}
			}
		} else if (action == 0x07) { // Increase Storage Slots
			slea.skip(1);
			int cash = slea.readInt();
			byte mode = slea.readByte();

			if (mode == 0) {
				if (cs.getCash(cash) < 4000)
					return;

				if (chr.getStorage().gainSlots(4)) {
					c.getSession().write(MaplePacketCreator.showBoughtStorageSlots(chr.getStorage().getSlots()));
					cs.gainCash(cash, -4000);
					c.getSession().write(MaplePacketCreator.showCash(chr));
				}
			} else {
				CashItem cItem = CashItemFactory.getItem(slea.readInt());

				if (!canBuy(cItem, cs.getCash(cash)))
					return;

				if (chr.getStorage().gainSlots(8)) {
					c.getSession().write(MaplePacketCreator.showBoughtStorageSlots(chr.getStorage().getSlots()));
					cs.gainCash(cash, -cItem.getPrice());
					c.getSession().write(MaplePacketCreator.showCash(chr));
				}
			}
    		} else if (action == 0x0D) { // Take from Cash Inventory
			IItem item = cs.findByCashId(slea.readInt());

			if (item == null)
				return;

			if (chr.getInventory(MapleItemInformationProvider.getInstance().getInventoryType(item.getItemId())).addItem(item) != -1) {
				cs.removeFromInventory(item);
				c.getSession().write(MaplePacketCreator.takeFromCashInventory(item));
			}

		} else if (action == 0x0E) { // Put into Cash Inventory
			int cashId = slea.readInt();
			slea.skip(4);
			MapleInventory mi = chr.getInventory(MapleInventoryType.getByType(slea.readByte()));
			IItem item = mi.findByCashId(cashId);

			if (item == null)
				return;

			cs.addToInventory(item);
			mi.removeSlot(item.getPosition());
			c.getSession().write(MaplePacketCreator.putIntoCashInventory(item, c.getAccID()));
                        
        } else if (action == 0x1C) { //crush ring (action 28)
            if (checkBirthday(c, slea.readInt())) {
                int toCharge = slea.readInt();
                int SN = slea.readInt();
                String recipient = slea.readMapleAsciiString();
                String text = slea.readMapleAsciiString();
                CashItem ring = CashItemFactory.getItem(SN);
                MapleCharacter partnerChar = c.getChannelServer().getPlayerStorage().getCharacterByName(recipient);
                if (partnerChar == null) {
                    c.getPlayer().getClient().getSession().write(MaplePacketCreator.serverNotice(1, "The partner you specified cannot be found.\r\nPlease make sure your partner is online and in the same channel."));
                } else {
                    if (partnerChar.getGender() == c.getPlayer().getGender()) {
                        c.getPlayer().dropMessage("You and your partner are the same gender, please buy a friendship ring.");
                        return;
                    }
                    //c.getSession().write(MaplePacketCreator.showBoughtCashItem(ring, c.getAccID()));
                    cs.gainCash(toCharge, -ring.getPrice());
                    MapleRing.createRing(ring.getItemId(), c.getPlayer(), partnerChar, text);
                    c.getPlayer().getClient().getSession().write(MaplePacketCreator.serverNotice(1, "Successfully created a ring for both you and your partner!"));
                }
            } else {
                c.getPlayer().dropMessage("The birthday you entered was incorrect.");
            }
            showCS(c); 
        } else if (action == 0x1F) { // everything is 1 meso...
            int itemId = CashItemFactory.getItem(slea.readInt()).getItemId();
            if (c.getPlayer().getMeso() > 0) {
                if (itemId == 4031180 || itemId == 4031192 || itemId == 4031191) {
                    c.getPlayer().gainMeso(-1, false);
                    MapleInventoryManipulator.addById(c, itemId, (short) 1);
                    c.getSession().write(MaplePacketCreator.showBoughtQuestItem(itemId));
                }
            }
            showCS(c);
        } else if (action == 0x22) {
            if (checkBirthday(c, slea.readInt())) {
                int payment = slea.readByte();
                slea.skip(3); //0s
                int snID = slea.readInt();
                CashItem ring = CashItemFactory.getItem(snID);
                String sentTo = slea.readMapleAsciiString();
                int available = slea.readShort() - 1;
                String text = slea.readAsciiString(available);
                slea.readByte();
                MapleCharacter partner = c.getChannelServer().getPlayerStorage().getCharacterByName(sentTo);
                if (partner == null) {
                    c.getPlayer().dropMessage("The partner you specified cannot be found.\r\nPlease make sure your partner is online and in the same channel.");
                } else {
                   // c.getSession().write(MaplePacketCreator.showBoughtCashItem(ring, c.getAccID()));
                    cs.gainCash(payment, -ring.getPrice());
                    MapleRing.createRing(ring.getItemId(), c.getPlayer(), partner, text);
                    c.getPlayer().getClient().getSession().write(MaplePacketCreator.serverNotice(1, "Successfully created a ring for both you and your partner!"));
                }
            } else {
                c.getPlayer().dropMessage("The birthday you entered was incorrect.");
            }
            showCS(c);
        } else {
            System.out.println(slea);
        }
    }

    private static final void showCS(MapleClient c) {
        c.getSession().write(MaplePacketCreator.showCash(c.getPlayer()));
        c.getSession().write(MaplePacketCreator.enableCSUse());
        c.getSession().write(MaplePacketCreator.enableActions());
    }

    private boolean checkBirthday(MapleClient c, int idate) {
        int year = idate / 10000;
        int month = (idate - year * 10000) / 100;
        int day = idate - year * 10000 - month * 100;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(0);
        cal.set(year, month - 1, day);
        return c.checkBirthDate(cal);
    }

	public boolean canBuy(CashItem item, int cash) {
		return item != null && item.isOnSale() && item.getPrice() <= cash;
	}
}